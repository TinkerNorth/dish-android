// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

    fun onOpenGamepad()

    /** User picked a new controller type (Xbox/PS) for a satellite-bound slot. */
    fun onChangeDeviceType(
        slotId: String,
        connectionId: String,
        type: Int,
    )

    /**
     * User flipped the per-slot motion toggle on the controller row.
     * Persisted by [com.tinkernorth.dish.source.store.MotionEnabledStore]
     * via the ViewModel; the dish stops emitting motion samples for the
     * slot (listener gate) and clears the `CAP_MOTION` bit at the next
     * `MSG_CONTROLLER_ADD` for it (cap-word reflection — see
     * [com.tinkernorth.dish.composer.MotionCapability.toCapBits]).
     */
    fun onMotionEnabledChanged(
        slotId: String,
        enabled: Boolean,
    )

    /**
     * User picked a touchpad routing mode on the virtual slot's satellite
     * row. [mode] is a [com.tinkernorth.dish.repository.TouchpadModeValue]
     * string (`"off"` / `"ds4"` / `"mouse"`). The ViewModel persists the
     * pick locally and pushes it to the satellite — server rejections are
     * surfaced as toasts so the user understands an unsupported pick.
     */
    fun onChangeTouchpadMode(
        connectionId: String,
        mode: String,
    )

    /**
     * User tapped "Open Touchpad" on the slot card. Implementation launches
     * [com.tinkernorth.dish.ui.main.TouchpadOverlayActivity] with the slot's
     * bound connection id + current touchpad mode (so the surface paints the
     * right Pad/Mouse visual, and the wire payload is sent under the slot's
     * own controllerIndex). Only fires when the slot is bound to a Connected
     * satellite and the mode is non-Off; the button is hidden otherwise.
     */
    fun onOpenTouchpad(slotId: String)
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
        /**
         * The latest motion capability snapshot for this slot. Drives the
         * per-slot motion toggle: the switch is checked iff
         * [MotionCapability.userEnabled], and disabled iff
         * `!hasGyro || !hostHasSinkForType` (with subtitle text explaining
         * which limit is in effect). Defaults to [MotionCapability.Off] so
         * a row built before the composer has emitted reads as
         * "hardware-not-yet-confirmed" rather than crashing on a missing
         * lookup.
         */
        val motionCap: MotionCapability = MotionCapability.Off,
        /**
         * Resolved per-satellite touchpad routing mode (connectionId →
         * [TouchpadModeValue]). Drives the touchpad-mode picker chips on
         * the virtual slot's satellite row AND whether the "Open Touchpad"
         * button is visible (hidden when the mode is Off). Empty until the
         * ViewModel's composer has emitted — a row built before then reads
         * every connection as Off, so the picker is shown with Off selected
         * but the launch button stays hidden.
         */
        val touchpadModes: Map<String, String> = emptyMap(),
    )

    private val expandedIds = mutableSetOf(VIRTUAL_SLOT_ID)

    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
        motionCapabilities: Map<String, MotionCapability> = emptyMap(),
        touchpadModes: Map<String, String> = emptyMap(),
    ) {
        submitList(
            slots.map { slot ->
                Row(
                    slot = slot,
                    connections = connections,
                    expanded = expandedIds.contains(slot.id),
                    motionCap = motionCapabilities[slot.id] ?: MotionCapability.Off,
                    touchpadModes = touchpadModes,
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

            // dotStatus's oval shape comes from `background="@drawable/dot_circle"`
            // in item_controller.xml — only the colour mutates at runtime.
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

        /**
         * Paint the two static overlay-launch buttons under Unbind: Open
         * Gamepad and Open Touchpad. Pulled out of [bind] so the per-row
         * action stack is one cohesive read — and to keep [bind]'s line
         * count under the detekt LongMethod threshold.
         *
         * Open Gamepad is virtual-only — the on-screen touch gamepad is the
         * only sender, so a physical slot would mislead the user.
         *
         * Open Touchpad is available for any slot bound to a Connected
         * satellite when the satellite's resolved touchpad mode is non-Off.
         * That covers the virtual slot AND physical pads without their own
         * touchpad surface (Xbox, Switch Pro, generic HID) — the user gets
         * an on-screen touchpad driven under the *slot's* controllerIndex,
         * so the receiver routes through whichever virtual device was
         * registered for it.
         */
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
            // Bluetooth is single-host: another slot already bound to this BT
            // connection blocks this slot from claiming it. Satellites accept
            // multiple slots side-by-side so we never disable for sat.
            val ownedByOther =
                c.kind == ConnectionKind.BLUETOOTH && c.boundSlotIds.any { it != slot.id }
            // Only reachable when [connectionsVisibleInPicker] kept it via the
            // bound-holdover rule — those rows render dimmed and don't accept
            // a click (the explicit Unbind button below is the action surface).
            val unreachable = !c.live.isAvailableForPicker()

            // Selected drives the picker_row_bg state-list so the border flips
            // to colorPrimary when this slot is bound to this connection.
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
            // Per-slot Xbox/PS toggle for the satellite this slot is bound to.
            // Bluetooth's controller type is fixed by the remembered host so
            // we don't render a switcher there.
            if (bound && c.kind == ConnectionKind.SATELLITE) {
                rb.llPickerControls.addView(buildTypeToggle(inflater, rb.llPickerControls, slot, c))
                // Per-slot motion (gyro) on/off switch. Same gating as the
                // type toggle — only meaningful for a satellite-bound slot
                // because Bluetooth-HID has no motion channel and motion
                // is a satellite-path feature.
                rb.llPickerControls.addView(buildMotionToggle(inflater, rb.llPickerControls, slot, motionCap))
                // Touchpad routing-mode picker — applies to both the on-screen
                // virtual touchpad AND any physical slot bound here, since the
                // satellite's per-device mode is shared across all of this
                // dish's slots. The launch button itself lives in the card's
                // static action stack (under Unbind), not here.
                if (c.live == LinkState.Connected) {
                    rb.llPickerControls.addView(
                        buildTouchpadModePicker(inflater, rb.llPickerControls, c, touchpadMode),
                    )
                }
            }
            parent.addView(rb.root)
        }

        /**
         * Header row inside a bind-picker entry: 22dp v6 brand glyph on the
         * leading edge, bold title to its right. The glyph reads the kind+live
         * state straight from [ConnectionSummary], so a row's silhouette
         * tracks live SSE updates without a separate refresh path.
         */
        private fun bindConnectionHeader(
            rb: PickerConnectionRowBinding,
            c: ConnectionSummary,
        ) {
            // Glyph rides on tvPickerTitle as a leading compound drawable
            // (collapsed from the prior ivPickerGlyph ImageView for perf in
            // this RecyclerView item). Sized to @dimen/icon_picker_glyph
            // rather than the vector's intrinsic 24dp.
            setStartCompoundDrawable(
                rb.tvPickerTitle,
                glyphForConnection(c.kind, c.live),
                R.dimen.icon_picker_glyph,
            )
            // Saved/Stale only reach this picker when the slot is currently
            // bound to that connection — surface "offline"/"needs pairing"
            // so the bound row reads as disconnected rather than silent.
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

        /**
         * Bind the per-slot motion (gyro) on/off row that lives under the
         * Xbox/PS chips in the expanded controller card. The switch
         * reflects [MotionCapability.userEnabled]; the subtitle text
         * explains the gating in plain language whenever motion CAN'T flow
         * (no gyro hardware, host has no sink for the chosen type, source
         * paused for other reasons). The switch is **disabled** for hard
         * limits the user can't fix by flipping it — no gyro and
         * no-host-sink — so the user is steered toward the actionable
         * remedy (different hardware, or change controller type) instead.
         */
        private fun buildMotionToggle(
            inflater: LayoutInflater,
            parent: ViewGroup,
            slot: ControllerSlot,
            cap: MotionCapability,
        ): View {
            val rb = PickerMotionToggleBinding.inflate(inflater, parent, false)
            // Subtitle: the most relevant "why" for the current state. Mirrors
            // the precedence in MotionIndicatorState.of() — hardware first,
            // then host-sink, then "on / off" status — so the text here is
            // consistent with what the overlay pill says.
            val subtitleResId =
                when {
                    !cap.hasGyro -> R.string.controller_motion_toggle_subtitle_no_gyro
                    !cap.hostHasSinkForType ->
                        R.string.controller_motion_toggle_subtitle_no_host_sink
                    cap.userEnabled -> R.string.controller_motion_toggle_subtitle_on
                    else -> R.string.controller_motion_toggle_subtitle_off
                }
            rb.tvMotionSubtitle.setText(subtitleResId)

            // The Switch itself. Disabled when there's nothing the user can
            // do by flipping it — no hardware, or the host backend has no
            // sink for the slot's controller type. The subtitle text above
            // already explains the limit.
            val enabledForUser = cap.hasGyro && cap.hostHasSinkForType
            // setOnCheckedChangeListener fires on programmatic setChecked
            // too — null it before the assignment so the first paint (and
            // every DiffUtil rebind through this same code) doesn't ping
            // the store. RecyclerView reuses views.
            rb.swMotion.setOnCheckedChangeListener(null)
            rb.swMotion.isChecked = cap.userEnabled && enabledForUser
            rb.swMotion.isEnabled = enabledForUser
            rb.swMotion.setOnCheckedChangeListener { _, isChecked ->
                listener.onMotionEnabledChanged(slot.id, isChecked)
            }
            return rb.root
        }

        /**
         * Touchpad routing-mode picker — three chips (Off / Pad / Mouse)
         * rendered under the motion toggle on a satellite-bound slot's row.
         * The selected chip mirrors the resolved mode in the row's
         * [Row.touchpadModes] map; tapping a chip fires
         * [SlotActionListener.onChangeTouchpadMode] which persists locally
         * and pushes to the server.
         */
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

        /**
         * Inflate one [chip_pickable.xml] chip, bind its label + checked
         * state + click handler. MaterialChip (FilterChip variant) drives
         * the selected/unselected fill, stroke, text colour, ripple, and
         * state layer via Widget.Dish.Chip — the adapter only flips
         * `isChecked` and wires the click handler.
         *
         * `isClickable = !checked` keeps the prior UX where the selected
         * chip can't be tapped again (single-choice picker: re-selecting
         * the current pick is a no-op). The OnClickListener path triggers
         * the listener which causes a row rebind that re-renders both
         * sibling chips with the new checked state — so the FilterChip's
         * built-in toggle-on-tap behaviour ends up consistent with the
         * single-choice semantics.
         */
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

        /**
         * Set [resId] as the leading (start) compound drawable on [tv],
         * sized to [sizeDimen] and with no tint (callers that need a tint
         * should wrap the drawable themselves — none currently do because
         * both the battery glyph and the connection-kind glyph are
         * deliberately two-tone and self-coloured).
         *
         * Returns the resolved drawable so animated-vector callers can
         * `start()` it without re-fetching from the TextView.
         *
         * Uses explicit bounds via [Drawable.setBounds] then
         * `setCompoundDrawablesRelative` because the icon dimens
         * (`icon_battery` = 20dp, `icon_picker_glyph` = 22dp) intentionally
         * differ from the vectors' intrinsic 24dp; the wrap-with-bounds
         * helper would render them at the wrong size.
         */
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
            // Battery glyph rides on the TextView as a leading compound
            // drawable (collapsed from the prior ivBattery ImageView for
            // perf in this RecyclerView item). The glyph is two-tone and
            // intentionally not tinted; size comes from @dimen/icon_battery
            // (not the drawable's intrinsic 24dp) so we set explicit bounds.
            val glyph = setStartCompoundDrawable(b.tvBattery, batteryIcon(battery), R.dimen.icon_battery)
            // ic_battery_charging is an AnimatedVectorDrawable — kick off its
            // fill-ramp / bolt-pulse loop. The other rungs are static, so for
            // them this is a harmless no-op.
            (glyph as? Animatable)?.start()
            b.tvBattery.text =
                battery.level?.let { ctx.getString(R.string.battery_percent, it) }
                    ?: ctx.getString(R.string.battery_unknown_level)
            // Only the percentage text carries the low-battery red; the icon is
            // left its own teal (a flat tint would collapse the white overlay).
            val colorRes = if (battery.isLow) R.color.colorError else R.color.colorMuted
            b.tvBattery.setTextColor(ctx.getColor(colorRes))
            // contentDescription lives on the TextView now that the icon is a
            // compound drawable; the announcement still tracks the live state.
            b.tvBattery.contentDescription = batteryDescription(ctx, battery)
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

        private fun setDot(
            v: View,
            colorRes: Int,
        ) {
            (v.background as? GradientDrawable)?.setColor(v.context.getColor(colorRes))
        }
    }

    /**
     * Inflate the empty-state label into the picker's connection-list
     * container. Width, top margin, text color, and text size all live in
     * `@layout/picker_empty_label`; this helper only binds the message.
     */
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
