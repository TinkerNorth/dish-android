// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.databinding.ChipPickableBinding
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.databinding.PickerChipRowBinding
import com.tinkernorth.dish.databinding.PickerConnectionRowBinding
import com.tinkernorth.dish.databinding.PickerMotionToggleBinding
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.ui.common.glyphForConnection

interface SlotActionListener {
    fun onSlotTapped(slotId: String)

    fun onBind(
        slotId: String,
        connectionId: String,
    )

    fun onUnbind(slotId: String)

    fun onTryDirectMode(slotId: String)

    fun onOpenGamepad()

    fun onChangeDeviceType(
        slotId: String,
        connectionId: String,
        type: Int,
    )

    fun onMotionEnabledChanged(
        slotId: String,
        enabled: Boolean,
    )

    fun onChangeTouchpadMode(
        connectionId: String,
        mode: String,
    )

    fun onOpenTouchpad(slotId: String)
}

internal fun LinkState.isAvailableForPicker(): Boolean =
    when (this) {
        LinkState.Connected, LinkState.Unstable -> true
        LinkState.Connecting,
        LinkState.Ready, LinkState.Found,
        LinkState.Saved, LinkState.Stale,
        -> false
    }

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
        val motionCap: MotionCapability = MotionCapability.Off,
        val touchpadModes: Map<String, String> = emptyMap(),
        val pathBadge: PathBadge? = null,
    )

    private val expandedIds = mutableSetOf(VIRTUAL_SLOT_ID)

    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
        motionCapabilities: Map<String, MotionCapability> = emptyMap(),
        touchpadModes: Map<String, String> = emptyMap(),
        pathBadges: Map<String, PathBadge> = emptyMap(),
    ) {
        submitList(
            slots.map { slot ->
                Row(
                    slot = slot,
                    connections = connections,
                    expanded = expandedIds.contains(slot.id),
                    motionCap = motionCapabilities[slot.id] ?: MotionCapability.Off,
                    touchpadModes = touchpadModes,
                    pathBadge = pathBadges[slot.id],
                )
            },
        )
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
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL

            b.ivControllerType.setImageResource(
                if (isVirtual) R.drawable.ic_gamepad_virtual else R.drawable.ic_gamepad,
            )
            b.tvControllerName.text = slot.name
            b.tvSlotStatus.text = slotStatusText(slot)
            bindBattery(slot.battery)
            bindPathBadge(row.pathBadge, slot.id)

            setDot(
                b.dotStatus,
                when {
                    slot.isDisconnecting -> R.color.colorWarning
                    slot.boundStatus?.live == LinkState.Connected -> R.color.colorSuccess
                    slot.boundStatus?.live == LinkState.Connecting -> R.color.colorPrimary
                    else -> R.color.colorMuted
                },
            )

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

            val canOpenGamepad =
                isVirtual && slot.boundStatus?.live == LinkState.Connected
            b.ivOpenGamepadQuick.visibility =
                if (canOpenGamepad && !row.expanded) View.VISIBLE else View.GONE
            b.ivOpenGamepadQuick.setOnClickListener { listener.onOpenGamepad() }

            b.llBody.visibility = if (row.expanded) View.VISIBLE else View.GONE
            if (!row.expanded) return

            if (slot.boundConnectionId != null) {
                b.btnUnbind.visibility = View.VISIBLE
                b.btnUnbind.setOnClickListener { listener.onUnbind(slot.id) }
            } else {
                b.btnUnbind.visibility = View.GONE
            }

            b.llConnectionList.removeAllViews()
            val visible = connectionsVisibleInPicker(row.connections, slot.boundConnectionId)
            when {
                row.connections.isEmpty() ->
                    addEmptyLabel(b.llConnectionList, R.string.picker_empty_no_connections)
                visible.isEmpty() ->
                    addEmptyLabel(b.llConnectionList, R.string.picker_empty_no_available)
                else ->
                    visible.forEach { summary ->
                        addConnectionRow(
                            b.llConnectionList,
                            slot,
                            summary,
                            row.motionCap,
                            row.touchpadModes[summary.id] ?: TouchpadModeValue.OFF,
                        )
                    }
            }

            bindOverlayLaunchButtons(row, isVirtual)
        }

        private fun bindOverlayLaunchButtons(
            row: Row,
            isVirtual: Boolean,
        ) {
            val slot = row.slot
            if (isVirtual && slot.boundStatus?.live == LinkState.Connected) {
                b.btnOpenGamepad.visibility = View.VISIBLE
                b.btnOpenGamepad.setOnClickListener { listener.onOpenGamepad() }
            } else {
                b.btnOpenGamepad.visibility = View.GONE
            }

            val boundCid = slot.boundConnectionId
            val boundConn = slot.boundStatus
            val touchpadModeForBound =
                if (boundCid != null) {
                    row.touchpadModes[boundCid] ?: TouchpadModeValue.OFF
                } else {
                    TouchpadModeValue.OFF
                }
            val canOpenTouchpad =
                boundConn?.live == LinkState.Connected &&
                    boundConn.kind == ConnectionKind.SATELLITE &&
                    touchpadModeForBound != TouchpadModeValue.OFF
            if (canOpenTouchpad) {
                b.btnOpenTouchpad.visibility = View.VISIBLE
                b.btnOpenTouchpad.setOnClickListener { listener.onOpenTouchpad(slot.id) }
            } else {
                b.btnOpenTouchpad.visibility = View.GONE
            }
        }

        private fun addConnectionRow(
            parent: LinearLayout,
            slot: ControllerSlot,
            c: ConnectionSummary,
            motionCap: MotionCapability,
            touchpadMode: String,
        ) {
            val ctx = parent.context
            val inflater = LayoutInflater.from(ctx)
            val rb = PickerConnectionRowBinding.inflate(inflater, parent, false)
            val bound = slot.boundConnectionId == c.id
            // BT is single-host; satellites accept multiple slots.
            val ownedByOther =
                c.kind == ConnectionKind.BLUETOOTH && c.boundSlotIds.any { it != slot.id }
            val unreachable = !c.live.isAvailableForPicker()

            rb.root.isSelected = bound
            rb.root.alpha =
                when {
                    ownedByOther -> 0.5f
                    unreachable -> 0.6f
                    else -> 1f
                }
            val clickable = !ownedByOther && !unreachable
            rb.root.isClickable = clickable
            if (clickable) {
                rb.root.setOnClickListener { listener.onBind(slot.id, c.id) }
            } else {
                rb.root.setOnClickListener(null)
            }

            bindConnectionHeader(rb, c)
            bindConnectionDetail(rb, ctx, c, bound, ownedByOther)
            if (bound && c.kind == ConnectionKind.SATELLITE) {
                rb.llPickerControls.addView(buildTypeToggle(inflater, rb.llPickerControls, slot, c))
                rb.llPickerControls.addView(buildMotionToggle(inflater, rb.llPickerControls, slot, motionCap))
                if (c.live == LinkState.Connected) {
                    rb.llPickerControls.addView(
                        buildTouchpadModePicker(inflater, rb.llPickerControls, c, touchpadMode),
                    )
                }
            }
            parent.addView(rb.root)
        }

        private fun bindConnectionHeader(
            rb: PickerConnectionRowBinding,
            c: ConnectionSummary,
        ) {
            setStartCompoundDrawable(
                rb.tvPickerTitle,
                glyphForConnection(c.kind, c.live),
                R.dimen.icon_picker_glyph,
            )
            val ctx = rb.root.context
            val statusSuffix =
                when (c.live) {
                    LinkState.Connected, LinkState.Unstable ->
                        ctx.getString(R.string.picker_status_online)
                    LinkState.Connecting -> ctx.getString(R.string.picker_status_connecting)
                    LinkState.Saved -> ctx.getString(R.string.picker_status_offline)
                    LinkState.Stale -> ctx.getString(R.string.picker_status_needs_pairing)
                    LinkState.Found, LinkState.Ready -> ""
                }
            rb.tvPickerTitle.text = ctx.getString(R.string.picker_title_with_status, c.label, statusSuffix)
        }

        private fun bindConnectionDetail(
            rb: PickerConnectionRowBinding,
            ctx: android.content.Context,
            c: ConnectionSummary,
            bound: Boolean,
            ownedByOther: Boolean,
        ) {
            rb.tvPickerDetail.text =
                buildString {
                    append(c.detail)
                    when {
                        bound -> append(ctx.getString(R.string.picker_detail_bound_here))
                        ownedByOther -> append(ctx.getString(R.string.picker_detail_in_use))
                    }
                }
        }

        private fun buildTypeToggle(
            inflater: LayoutInflater,
            parent: ViewGroup,
            slot: ControllerSlot,
            c: ConnectionSummary,
        ): View {
            val current = c.satelliteControllerTypes[slot.id] ?: CONTROLLER_TYPE_XBOX
            val rb = PickerChipRowBinding.inflate(inflater, parent, false)
            val ctx = rb.root.context
            rb.tvChipRowLabel.text = ctx.getString(R.string.picker_type_label)
            rb.llChips.addView(
                buildChip(
                    inflater,
                    rb.llChips,
                    label = ctx.getString(R.string.picker_type_xbox),
                    selected = current == CONTROLLER_TYPE_XBOX,
                ) { listener.onChangeDeviceType(slot.id, c.id, CONTROLLER_TYPE_XBOX) },
            )
            rb.llChips.addView(
                buildChip(
                    inflater,
                    rb.llChips,
                    label = ctx.getString(R.string.picker_type_playstation),
                    selected = current == CONTROLLER_TYPE_PLAYSTATION,
                ) { listener.onChangeDeviceType(slot.id, c.id, CONTROLLER_TYPE_PLAYSTATION) },
            )
            return rb.root
        }

        private fun buildMotionToggle(
            inflater: LayoutInflater,
            parent: ViewGroup,
            slot: ControllerSlot,
            cap: MotionCapability,
        ): View {
            val rb = PickerMotionToggleBinding.inflate(inflater, parent, false)
            val subtitleResId =
                when {
                    !cap.hasGyro -> R.string.controller_motion_toggle_subtitle_no_gyro
                    !cap.hostHasSinkForType ->
                        R.string.controller_motion_toggle_subtitle_no_host_sink
                    cap.userEnabled -> R.string.controller_motion_toggle_subtitle_on
                    else -> R.string.controller_motion_toggle_subtitle_off
                }
            rb.tvMotionSubtitle.setText(subtitleResId)

            val enabledForUser = cap.hasGyro && cap.hostHasSinkForType
            // Null listener before programmatic setChecked so RecyclerView rebinds don't ping the store.
            rb.swMotion.setOnCheckedChangeListener(null)
            rb.swMotion.isChecked = cap.userEnabled && enabledForUser
            rb.swMotion.isEnabled = enabledForUser
            rb.swMotion.setOnCheckedChangeListener { _, isChecked ->
                listener.onMotionEnabledChanged(slot.id, isChecked)
            }
            return rb.root
        }

        private fun buildTouchpadModePicker(
            inflater: LayoutInflater,
            parent: ViewGroup,
            c: ConnectionSummary,
            currentMode: String,
        ): View {
            val rb = PickerChipRowBinding.inflate(inflater, parent, false)
            val ctx = rb.root.context
            rb.tvChipRowLabel.text = ctx.getString(R.string.touchpad_mode_label)
            rb.llChips.addView(
                buildChip(
                    inflater,
                    rb.llChips,
                    label = ctx.getString(R.string.touchpad_mode_off),
                    selected = currentMode == TouchpadModeValue.OFF,
                ) { listener.onChangeTouchpadMode(c.id, TouchpadModeValue.OFF) },
            )
            rb.llChips.addView(
                buildChip(
                    inflater,
                    rb.llChips,
                    label = ctx.getString(R.string.touchpad_mode_pad),
                    selected = currentMode == TouchpadModeValue.DS4,
                ) { listener.onChangeTouchpadMode(c.id, TouchpadModeValue.DS4) },
            )
            rb.llChips.addView(
                buildChip(
                    inflater,
                    rb.llChips,
                    label = ctx.getString(R.string.touchpad_mode_mouse),
                    selected = currentMode == TouchpadModeValue.MOUSE,
                ) { listener.onChangeTouchpadMode(c.id, TouchpadModeValue.MOUSE) },
            )
            return rb.root
        }

        // Single-choice picker: clearing isClickable on the selected chip suppresses the
        // FilterChip's built-in toggle-on-tap so re-selecting the current pick is a no-op;
        // a real change rebinds the row and re-renders both chips with the new state.
        private fun buildChip(
            inflater: LayoutInflater,
            parent: ViewGroup,
            label: String,
            selected: Boolean,
            onClick: () -> Unit,
        ): Chip {
            val chip = ChipPickableBinding.inflate(inflater, parent, false).root
            chip.text = label
            chip.isChecked = selected
            chip.isClickable = !selected
            if (selected) {
                chip.setOnClickListener(null)
            } else {
                chip.setOnClickListener { onClick() }
            }
            return chip
        }

        // Explicit bounds because icon dimens (20/22dp) differ from vector intrinsic 24dp.
        private fun setStartCompoundDrawable(
            tv: TextView,
            @DrawableRes resId: Int,
            @DimenRes sizeDimen: Int,
        ): Drawable? {
            val drawable = AppCompatResources.getDrawable(tv.context, resId) ?: return null
            val size = tv.resources.getDimensionPixelSize(sizeDimen)
            drawable.setBounds(0, 0, size, size)
            tv.setCompoundDrawablesRelative(drawable, null, null, null)
            return drawable
        }

        private fun bindBattery(battery: BatteryUi?) {
            val ctx = b.root.context
            if (battery == null) {
                b.tvBattery.visibility = View.GONE
                return
            }
            b.tvBattery.visibility = View.VISIBLE
            val glyph = setStartCompoundDrawable(b.tvBattery, batteryIcon(battery), R.dimen.icon_battery)
            // ic_battery_charging is an AnimatedVectorDrawable; start() is a no-op on static rungs.
            (glyph as? Animatable)?.start()
            b.tvBattery.text =
                battery.level?.let { ctx.getString(R.string.battery_percent, it) }
                    ?: ctx.getString(R.string.battery_unknown_level)
            val colorRes = if (battery.isLow) R.color.colorError else R.color.colorMuted
            b.tvBattery.setTextColor(ctx.getColor(colorRes))
            b.tvBattery.contentDescription = batteryDescription(ctx, battery)
        }

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

        private fun bindPathBadge(
            badge: PathBadge?,
            slotId: String,
        ) {
            if (badge == null) {
                b.tvPathBadge.visibility = View.GONE
                b.tvPathReason.visibility = View.GONE
                b.tvPathReason.setOnClickListener(null)
                return
            }
            val ctx = b.root.context
            b.tvPathBadge.visibility = View.VISIBLE
            b.tvPathBadge.text = badge.label
            val colorRes = if (badge.isDirect) R.color.colorSuccess else R.color.colorMuted
            b.tvPathBadge.setTextColor(ctx.getColor(colorRes))
            val subtitle = badge.subtitle
            if (subtitle.isNullOrBlank()) {
                b.tvPathReason.visibility = View.GONE
                b.tvPathReason.setOnClickListener(null)
                return
            }
            b.tvPathReason.visibility = View.VISIBLE
            b.tvPathReason.text = subtitle
            if (badge.actionable) {
                val attr = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
                b.tvPathReason.setBackgroundResource(attr.resourceId)
                b.tvPathReason.setTextColor(ctx.getColor(R.color.colorPrimary))
                b.tvPathReason.isClickable = true
                b.tvPathReason.isFocusable = true
                b.tvPathReason.setOnClickListener { listener.onTryDirectMode(slotId) }
            } else {
                b.tvPathReason.setTextColor(ctx.getColor(R.color.colorMuted))
                b.tvPathReason.setOnClickListener(null)
                b.tvPathReason.isClickable = false
                b.tvPathReason.isFocusable = false
                b.tvPathReason.background = null
            }
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

        private fun setDot(
            v: View,
            colorRes: Int,
        ) {
            (v.background as? GradientDrawable)?.setColor(v.context.getColor(colorRes))
        }
    }

    private fun addEmptyLabel(
        parent: LinearLayout,
        @androidx.annotation.StringRes messageRes: Int,
    ) {
        val inflater = LayoutInflater.from(parent.context)
        val label = inflater.inflate(R.layout.picker_empty_label, parent, false) as TextView
        label.setText(messageRes)
        parent.addView(label)
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
