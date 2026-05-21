// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.ui.common.glyphForConnection

interface SlotActionListener {
    fun onSlotTapped(slotId: String)

    fun onBind(
        slotId: String,
        connectionId: String,
    )

    fun onUnbind(slotId: String)

    fun onOpenGamepad()

    /** User picked a new controller type (Xbox/PS) for a satellite-bound slot. */
    fun onChangeDeviceType(
        slotId: String,
        connectionId: String,
        type: Int,
    )
}

/**
 * Whether a connection in this state belongs in the slot's bind picker as a
 * normal, claimable target. Saved (offline) and Stale (needs pairing) drop
 * off because the user can't usefully route input to them right now — the
 * picker stays focused on the targets they can actually pick. A slot that
 * is *already* bound keeps its current connection visible regardless, see
 * [connectionsVisibleInPicker].
 */
internal fun LinkState.isAvailableForPicker(): Boolean =
    when (this) {
        LinkState.Connected, LinkState.Unstable,
        LinkState.Connecting,
        LinkState.Ready, LinkState.Found,
        -> true
        LinkState.Saved, LinkState.Stale -> false
    }

/**
 * Pure helper: the subset of [all] the per-slot picker should display.
 * Includes every available connection plus the slot's currently-bound one
 * (even when unreachable) so the user keeps a handle on what they're routing
 * to until they unbind or the connection recovers.
 */
internal fun connectionsVisibleInPicker(
    all: List<ConnectionSummary>,
    boundConnectionId: String?,
): List<ConnectionSummary> = all.filter { it.live.isAvailableForPicker() || it.id == boundConnectionId }

class ControllerAdapter(
    private val listener: SlotActionListener,
) : ListAdapter<ControllerAdapter.Row, ControllerAdapter.VH>(Diff) {
    data class Row(
        val slot: ControllerSlot,
        val connections: List<ConnectionSummary>,
        val expanded: Boolean,
    )

    private val expandedIds = mutableSetOf(VIRTUAL_SLOT_ID)

    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
    ) {
        submitList(slots.map { Row(it, connections, expandedIds.contains(it.id)) })
    }

    fun toggleExpanded(slotId: String) {
        if (!expandedIds.add(slotId)) expandedIds.remove(slotId)
        submitList(currentList.map { it.copy(expanded = expandedIds.contains(it.slot.id)) })
    }

    inner class VH(
        private val b: ItemControllerBinding,
    ) : RecyclerView.ViewHolder(b.root) {
        @Suppress("CyclomaticComplexMethod")
        fun bind(row: Row) {
            val slot = row.slot
            val ctx = b.root.context
            val dp = ctx.resources.displayMetrics.density
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL

            b.ivControllerType.setImageResource(
                if (isVirtual) R.drawable.ic_gamepad_virtual else R.drawable.ic_gamepad,
            )
            b.tvControllerName.text = slot.name
            b.tvSlotStatus.text = slotStatusText(slot)
            bindBattery(slot.battery)

            initDot(b.dotStatus)
            setDot(
                b.dotStatus,
                when {
                    slot.isDisconnecting -> R.color.colorWarning
                    slot.boundStatus?.live == LinkState.Connected -> R.color.colorSuccess
                    slot.boundStatus?.live == LinkState.Connecting -> R.color.colorPrimary
                    else -> R.color.colorMuted
                },
            )

            // Connection-target glyph: mirrors the same icon family the
            // ConnectionsActivity row uses, so a slot and its bound row read
            // as the same thing. Hidden when the slot is unbound — the
            // gamepad-type icon on the left already names the slot itself.
            val bound = slot.boundStatus
            if (bound == null) {
                b.ivBoundKind.visibility = View.GONE
            } else {
                b.ivBoundKind.visibility = View.VISIBLE
                b.ivBoundKind.setImageResource(glyphForConnection(bound.kind, bound.live))
            }
            b.root.alpha = if (slot.isDisconnecting) 0.5f else 1f

            b.cardRoot.strokeColor =
                ctx.getColor(
                    if (row.expanded) R.color.colorPrimary else R.color.colorCardStroke,
                )
            b.root.setOnClickListener { listener.onSlotTapped(slot.id) }

            b.ivChevron.rotation = if (row.expanded) 180f else 0f

            // Quick-launch button surfaces only on the collapsed virtual card
            // when a connection is live, so the user can jump straight into
            // the overlay without expanding.
            val canOpenGamepad =
                isVirtual && slot.boundStatus?.live == LinkState.Connected
            b.ivOpenGamepadQuick.visibility =
                if (canOpenGamepad && !row.expanded) View.VISIBLE else View.GONE
            b.ivOpenGamepadQuick.setOnClickListener { listener.onOpenGamepad() }

            b.llBody.visibility = if (row.expanded) View.VISIBLE else View.GONE
            if (!row.expanded) return

            // Unbind button
            if (slot.boundConnectionId != null) {
                b.btnUnbind.visibility = View.VISIBLE
                b.btnUnbind.setOnClickListener { listener.onUnbind(slot.id) }
            } else {
                b.btnUnbind.visibility = View.GONE
            }

            // Connection list. Filtered to "available now" plus the
            // bound-but-unreachable holdover; see [connectionsVisibleInPicker].
            b.llConnectionList.removeAllViews()
            val visible = connectionsVisibleInPicker(row.connections, slot.boundConnectionId)
            when {
                row.connections.isEmpty() ->
                    addLabel(
                        b.llConnectionList,
                        dp,
                        ctx.getString(R.string.picker_empty_no_connections),
                        R.color.colorMuted,
                    )
                visible.isEmpty() ->
                    addLabel(
                        b.llConnectionList,
                        dp,
                        ctx.getString(R.string.picker_empty_no_available),
                        R.color.colorMuted,
                    )
                else ->
                    visible.forEach { summary ->
                        addConnectionRow(b.llConnectionList, dp, slot, summary)
                    }
            }

            // Virtual-only: open gamepad button
            if (isVirtual && slot.boundStatus?.live == LinkState.Connected) {
                b.btnOpenGamepad.visibility = View.VISIBLE
                b.btnOpenGamepad.setOnClickListener { listener.onOpenGamepad() }
            } else {
                b.btnOpenGamepad.visibility = View.GONE
            }
        }

        private fun addConnectionRow(
            parent: LinearLayout,
            dp: Float,
            slot: ControllerSlot,
            c: ConnectionSummary,
        ) {
            val ctx = parent.context
            val bound = slot.boundConnectionId == c.id
            // Bluetooth is single-host: another slot already bound to this BT
            // connection blocks this slot from claiming it. Satellites accept
            // multiple slots side-by-side so we never disable for sat.
            val ownedByOther =
                c.kind == ConnectionKind.BLUETOOTH && c.boundSlotIds.any { it != slot.id }
            // Only reachable when [connectionsVisibleInPicker] kept it via the
            // bound-holdover rule — those rows render dimmed and don't accept
            // a click (the explicit Unbind button below is the action surface).
            val unreachable = !c.live.isAvailableForPicker()

            val row =
                buildConnectionRowContainer(ctx, dp, bound, ownedByOther, unreachable) {
                    if (!ownedByOther && !unreachable) listener.onBind(slot.id, c.id)
                }
            // Leading v6 brand glyph + stacked title/detail column. Mirrors
            // ConnectionsActivity row_connection.xml and dish-mac SlotCard's
            // expanded picker so the same connection looks the same wherever
            // it surfaces. The glyph reads the kind+live state straight from
            // [ConnectionSummary], so a row's silhouette tracks the live
            // SSE update without a separate refresh.
            row.addView(buildConnectionHeader(ctx, dp, c))
            row.addView(buildConnectionDetail(ctx, c, bound, ownedByOther))
            // Per-slot Xbox/PS toggle for the satellite this slot is bound to.
            // Bluetooth's controller type is fixed by the remembered host so
            // we don't render a switcher there.
            if (bound && c.kind == ConnectionKind.SATELLITE) {
                row.addView(buildTypeToggle(ctx, dp, slot, c))
            }
            parent.addView(row)
        }

        private fun buildConnectionRowContainer(
            ctx: android.content.Context,
            dp: Float,
            bound: Boolean,
            ownedByOther: Boolean,
            unreachable: Boolean,
            onClick: () -> Unit,
        ): LinearLayout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (10 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        setColor(ctx.getColor(R.color.colorBackground))
                        cornerRadius = 6 * dp
                        setStroke(
                            (1 * dp).toInt(),
                            ctx.getColor(if (bound) R.color.colorPrimary else R.color.colorOutline),
                        )
                    }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (6 * dp).toInt() }
                alpha =
                    when {
                        ownedByOther -> 0.5f
                        unreachable -> 0.6f
                        else -> 1f
                    }
                val clickable = !ownedByOther && !unreachable
                isClickable = clickable
                if (clickable) setOnClickListener { onClick() }
            }

        /**
         * Header row inside a bind-picker entry: 22dp v6 brand glyph on the
         * leading edge, bold title to its right. The glyph replaces the old
         * "📡 "/"🔗 " emoji prefix so the picker shares the same icon family
         * as the Connections page rows and the slot card's bound-kind glyph
         * — `boundKindGlyph()` is the single source of truth for kind+state
         * → drawable.
         */
        private fun buildConnectionHeader(
            ctx: android.content.Context,
            dp: Float,
            c: ConnectionSummary,
        ): LinearLayout {
            // Saved/Stale only reach this picker when the slot is currently
            // bound to that connection — surface "offline"/"needs pairing"
            // so the bound row reads as disconnected rather than silent.
            val statusSuffix =
                when (c.live) {
                    LinkState.Connected, LinkState.Unstable -> ctx.getString(R.string.picker_status_online)
                    LinkState.Connecting -> ctx.getString(R.string.picker_status_connecting)
                    LinkState.Saved -> ctx.getString(R.string.picker_status_offline)
                    LinkState.Stale -> ctx.getString(R.string.picker_status_needs_pairing)
                    LinkState.Found, LinkState.Ready -> ""
                }
            val title =
                TextView(ctx).apply {
                    text = "${c.label}$statusSuffix"
                    setTextColor(ctx.getColor(R.color.colorOnSurface))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                }
            val glyph =
                ImageView(ctx).apply {
                    setImageResource(glyphForConnection(c.kind, c.live))
                    layoutParams =
                        LinearLayout
                            .LayoutParams((22 * dp).toInt(), (22 * dp).toInt())
                            .apply { marginEnd = (8 * dp).toInt() }
                    contentDescription = null
                }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(glyph)
                addView(title)
            }
        }

        private fun buildConnectionDetail(
            ctx: android.content.Context,
            c: ConnectionSummary,
            bound: Boolean,
            ownedByOther: Boolean,
        ): TextView {
            val detail =
                buildString {
                    append(c.detail)
                    when {
                        bound -> append(ctx.getString(R.string.picker_detail_bound_here))
                        ownedByOther -> append(ctx.getString(R.string.picker_detail_in_use))
                    }
                }
            return TextView(ctx).apply {
                text = detail
                setTextColor(ctx.getColor(R.color.colorMuted))
                textSize = 11f
                typeface = Typeface.MONOSPACE
            }
        }

        private fun buildTypeToggle(
            ctx: android.content.Context,
            dp: Float,
            slot: ControllerSlot,
            c: ConnectionSummary,
        ): View {
            val current = c.satelliteControllerTypes[slot.id] ?: CONTROLLER_TYPE_XBOX
            val container =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = (8 * dp).toInt() }
                }
            container.addView(
                TextView(ctx).apply {
                    text = ctx.getString(R.string.picker_type_label)
                    setTextColor(ctx.getColor(R.color.colorMuted))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                marginEnd = (10 * dp).toInt()
                            }
                },
            )
            container.addView(
                typeChip(
                    ctx,
                    dp,
                    label = ctx.getString(R.string.picker_type_xbox),
                    selected = current == CONTROLLER_TYPE_XBOX,
                ) { listener.onChangeDeviceType(slot.id, c.id, CONTROLLER_TYPE_XBOX) },
            )
            container.addView(
                typeChip(
                    ctx,
                    dp,
                    label = ctx.getString(R.string.picker_type_playstation),
                    selected = current == CONTROLLER_TYPE_PLAYSTATION,
                ) { listener.onChangeDeviceType(slot.id, c.id, CONTROLLER_TYPE_PLAYSTATION) },
            )
            return container
        }

        private fun typeChip(
            ctx: android.content.Context,
            dp: Float,
            label: String,
            selected: Boolean,
            onClick: () -> Unit,
        ): TextView =
            TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(
                    ctx.getColor(if (selected) R.color.colorOnPrimary else R.color.colorOnSurface),
                )
                val padH = (10 * dp).toInt()
                val padV = (4 * dp).toInt()
                setPadding(padH, padV, padH, padV)
                background =
                    GradientDrawable().apply {
                        setColor(
                            ctx.getColor(
                                if (selected) R.color.colorPrimary else R.color.colorBackground,
                            ),
                        )
                        cornerRadius = 4 * dp
                        setStroke((1 * dp).toInt(), ctx.getColor(R.color.colorOutline))
                    }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = (6 * dp).toInt() }
                isClickable = !selected
                if (!selected) setOnClickListener { onClick() }
            }

        private fun bindBattery(battery: BatteryUi?) {
            val ctx = b.root.context
            if (battery == null) {
                b.llBattery.visibility = View.GONE
                return
            }
            b.llBattery.visibility = View.VISIBLE
            b.ivBattery.setImageResource(batteryIcon(battery))
            // ic_battery_charging is an AnimatedVectorDrawable — kick off its
            // fill-ramp / bolt-pulse loop. The other rungs are static, so for
            // them this is a harmless no-op.
            (b.ivBattery.drawable as? Animatable)?.start()
            b.tvBattery.text =
                battery.level?.let { ctx.getString(R.string.battery_percent, it) }
                    ?: ctx.getString(R.string.battery_unknown_level)
            // Only the percentage text carries the low-battery red; the icon is
            // left its own teal (a flat tint would collapse the white overlay).
            val colorRes = if (battery.isLow) R.color.colorError else R.color.colorMuted
            b.tvBattery.setTextColor(ctx.getColor(colorRes))
            b.ivBattery.contentDescription = batteryDescription(ctx, battery)
        }

        /**
         * Pick the charge-ladder drawable for [battery]. Charging selects the
         * animated bolt icon; otherwise the level picks a rung, mirroring the
         * percent → file table the battery icon assets ship with. A null level
         * (a status-only pad) falls back to the generic glyph.
         */
        private fun batteryIcon(battery: BatteryUi): Int {
            if (battery.charging) return R.drawable.ic_battery_charging
            val level = battery.level ?: return R.drawable.ic_battery
            return when {
                level <= 0 -> R.drawable.ic_battery_empty
                level >= 90 -> R.drawable.ic_battery_full
                level >= 60 -> R.drawable.ic_battery_high
                level >= 35 -> R.drawable.ic_battery_mid
                level >= 15 -> R.drawable.ic_battery_low
                else -> R.drawable.ic_battery_critical
            }
        }

        /**
         * Build the dynamic battery `contentDescription` for screen readers:
         * the level (a percentage, or "level unknown" for a status-only pad)
         * plus a charging-state phrase. Kept off the static layout string so
         * the announced value tracks the live battery.
         */
        private fun batteryDescription(
            ctx: android.content.Context,
            battery: BatteryUi,
        ): String {
            val levelText =
                battery.level?.let { ctx.getString(R.string.battery_percent, it) }
                    ?: ctx.getString(R.string.battery_desc_level_unknown)
            val stateRes =
                when {
                    battery.isLow -> R.string.battery_state_low
                    battery.charging -> R.string.battery_state_charging
                    else -> R.string.battery_state_discharging
                }
            return ctx.getString(R.string.battery_desc, levelText, ctx.getString(stateRes))
        }

        private fun slotStatusText(s: ControllerSlot): String {
            val ctx = b.root.context
            return when {
                s.isDisconnecting -> ctx.getString(R.string.slot_status_disconnecting, s.disconnectTimeLeft)
                s.boundStatus?.live == LinkState.Connected ->
                    ctx.getString(R.string.slot_status_routing_to, s.boundStatus.label)
                s.boundStatus?.live == LinkState.Connecting -> ctx.getString(R.string.chip_status_connecting)
                s.boundConnectionId != null -> ctx.getString(R.string.slot_status_bound)
                else -> ctx.getString(R.string.slot_status_tap_to_bind)
            }
        }

        private fun initDot(v: View) {
            if (v.background is GradientDrawable) return
            v.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(v.context.getColor(R.color.colorMuted))
                }
        }

        private fun setDot(
            v: View,
            colorRes: Int,
        ) {
            (v.background as? GradientDrawable)?.setColor(v.context.getColor(colorRes))
        }
    }

    private fun addLabel(
        parent: LinearLayout,
        dp: Float,
        text: String,
        colorRes: Int,
    ) {
        val ctx = parent.context
        parent.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(ctx.getColor(colorRes))
                textSize = 12f
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (4 * dp).toInt() }
            },
        )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ) = VH(ItemControllerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
    ) = holder.bind(getItem(position))

    companion object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(
            o: Row,
            n: Row,
        ) = o.slot.id == n.slot.id

        override fun areContentsTheSame(
            o: Row,
            n: Row,
        ) = o == n
    }
}
