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
import com.tinkernorth.dish.data.network.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.data.network.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.data.network.LinkState
import com.tinkernorth.dish.databinding.ItemControllerBinding

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
                b.ivBoundKind.setImageResource(boundKindGlyph(bound.kind, bound.live))
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

            // Connection list
            b.llConnectionList.removeAllViews()
            if (row.connections.isEmpty()) {
                addLabel(b.llConnectionList, dp, "No connections yet — tap Manage above", R.color.colorMuted)
            } else {
                row.connections.forEach { summary ->
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

            val row =
                buildConnectionRowContainer(ctx, dp, bound, ownedByOther) {
                    if (!ownedByOther) listener.onBind(slot.id, c.id)
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
                alpha = if (ownedByOther) 0.5f else 1f
                isClickable = !ownedByOther
                if (!ownedByOther) setOnClickListener { onClick() }
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
            val statusSuffix =
                when (c.live) {
                    LinkState.Connected, LinkState.Unstable -> " • online"
                    LinkState.Connecting -> " • connecting…"
                    LinkState.Found, LinkState.Stale, LinkState.Saved, LinkState.Ready -> ""
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
                    setImageResource(boundKindGlyph(c.kind, c.live))
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
                        bound -> append(" • bound here")
                        ownedByOther -> append(" • in use")
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
                    text = "Type"
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
                    label = "Xbox",
                    selected = current == CONTROLLER_TYPE_XBOX,
                ) { listener.onChangeDeviceType(slot.id, c.id, CONTROLLER_TYPE_XBOX) },
            )
            container.addView(
                typeChip(
                    ctx,
                    dp,
                    label = "PlayStation",
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

        /**
         * v6 brand glyph that names the connection this slot is bound to.
         * Mirrors ConnectionsActivity.rowGlyphRes() so a slot's silhouette
         * matches the source row's silhouette at a glance. Satellite rows
         * carry the satellite glyph; Bluetooth rows carry the Berkana rune.
         */
        private fun boundKindGlyph(
            kind: ConnectionKind,
            state: LinkState,
        ): Int =
            when (kind) {
                ConnectionKind.SATELLITE ->
                    when (state) {
                        LinkState.Connected -> R.drawable.ic_satellite_connected
                        LinkState.Saved, LinkState.Stale -> R.drawable.ic_satellite_off
                        else -> R.drawable.ic_satellite
                    }
                ConnectionKind.BLUETOOTH ->
                    when (state) {
                        LinkState.Connected -> R.drawable.ic_bluetooth_connected
                        LinkState.Connecting -> R.drawable.ic_bluetooth_searching
                        LinkState.Saved, LinkState.Stale -> R.drawable.ic_bluetooth_off
                        else -> R.drawable.ic_bluetooth
                    }
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

        private fun slotStatusText(s: ControllerSlot) =
            when {
                s.isDisconnecting -> "Disconnecting… ${s.disconnectTimeLeft}s"
                s.boundStatus?.live == LinkState.Connected ->
                    "→ ${s.boundStatus.label}"
                s.boundStatus?.live == LinkState.Connecting -> "Connecting…"
                s.boundConnectionId != null -> "Bound"
                else -> "Tap to bind"
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
