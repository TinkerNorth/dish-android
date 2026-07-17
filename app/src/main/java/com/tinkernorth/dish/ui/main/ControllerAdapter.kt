// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.databinding.BindingDecisionRowBinding
import com.tinkernorth.dish.databinding.BindingPillBinding
import com.tinkernorth.dish.databinding.BindingValueMonoBinding
import com.tinkernorth.dish.databinding.BindingValueNoneBinding
import com.tinkernorth.dish.databinding.BindingValueNotBoundBinding
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.inputrate.SlotInputRates

interface SlotActionListener {
    fun onConfigure(slotId: String)

    fun onOpenGamepad(slotId: String)

    fun onOpenTouchpad(slotId: String)

    fun onManageDestinations()

    fun onReconnect(slotId: String)

    fun onUnbind(slotId: String)
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

// Unstable is degraded but still routing, so it counts as live alongside Connected.
internal fun LinkState.isLiveLink(): Boolean = this == LinkState.Connected || this == LinkState.Unstable

// The motion source can stream while motion is user-facing off (no host sink for the emulated
// type, broken backend): the card's motion rate hides in exactly the states the motion indicator
// renders as muted, so the two never disagree. Motion only carries to a Satellite, so the bound
// summary's kind and liveness gate it (the capability model omits link state).
internal fun motionRateUserFacingOn(
    cap: SlotCapabilities,
    boundStatus: ConnectionSummary?,
): Boolean =
    cap.inputOk(Feature.MOTION) &&
        cap.userWants(Feature.MOTION) &&
        boundStatus?.kind == ConnectionKind.SATELLITE &&
        boundStatus.live == LinkState.Connected &&
        cap.typeOk(Feature.MOTION) &&
        Feature.MOTION !in cap.runtimeDown

// Screen input can drive a slot only while an overlay surface exists for it: the on-screen
// gamepad for the virtual slot, or the slot's phone touchpad surface when it is openable
// (mode on AND the phone is the touch source; a pad streaming its own trackpad has no phone
// surface). Outside those states the card's screen rate reads Off.
internal fun screenRateUserFacingOn(
    inputType: SlotInputType,
    boundKind: ConnectionKind?,
    touchpad: TouchpadSlotUi?,
): Boolean =
    inputType == SlotInputType.VIRTUAL ||
        (boundKind == ConnectionKind.SATELLITE && touchpad?.openable == true)

class ControllerAdapter(
    private val listener: SlotActionListener,
) : ListAdapter<ControllerAdapter.Row, ControllerAdapter.VH>(Diff) {
    private val dismissedUnsteady = mutableSetOf<String>()

    data class Row(
        val slot: ControllerSlot,
        val connections: List<ConnectionSummary>,
        val motionCap: SlotCapabilities = SlotCapabilities.NONE,
        val touchpad: TouchpadSlotUi? = null,
        val pathCard: PathCard? = null,
        val inputRates: SlotInputRates? = null,
        val screenPeakHz: Int = 0,
    )

    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
        motionCapabilities: Map<String, SlotCapabilities> = emptyMap(),
        touchpadBySlot: Map<String, TouchpadSlotUi> = emptyMap(),
        pathCards: Map<String, PathCard> = emptyMap(),
        inputRates: Map<String, SlotInputRates> = emptyMap(),
        screenPeakHz: Int = 0,
    ) {
        submitList(
            slots.map { slot ->
                Row(
                    slot = slot,
                    connections = connections,
                    motionCap = motionCapabilities[slot.id] ?: SlotCapabilities.NONE,
                    touchpad = touchpadBySlot[slot.id],
                    pathCard = pathCards[slot.id],
                    inputRates = inputRates[slot.id],
                    screenPeakHz = screenPeakHz,
                )
            },
        )
    }

    inner class VH(
        private val b: ItemControllerBinding,
    ) : RecyclerView.ViewHolder(b.root) {
        private val ctx: Context get() = b.root.context
        private val inflater: LayoutInflater get() = LayoutInflater.from(ctx)

        private val connectionRow = decisionRow(R.string.binding_label_connection)
        private val destinationRow = decisionRow(R.string.binding_label_destination)
        private val emulateRow = decisionRow(R.string.binding_label_emulate)
        private val functionRow = decisionRow(null)
        private val rateRow = decisionRow(null)

        private val connectionPills = PillPool(connectionRow.valueContainer)
        private val emulatePills = PillPool(emulateRow.valueContainer)
        private val functionPills = PillPool(functionRow.valueContainer)
        private val ratePills = PillPool(rateRow.valueContainer)

        private val destinationMono = BindingValueMonoBinding.inflate(inflater, destinationRow.valueContainer, true)
        private val destinationNotBound = BindingValueNotBoundBinding.inflate(inflater, destinationRow.valueContainer, true)
        private val functionNone = BindingValueNoneBinding.inflate(inflater, functionRow.valueContainer, true)

        private val filledActions =
            List(MAX_FILLED_ACTIONS) {
                inflater.inflate(R.layout.binding_action_button, b.llActions, false) as MaterialButton
            }
        private val outlinedAction =
            inflater.inflate(R.layout.binding_action_button_outlined, b.llActions, false) as MaterialButton

        init {
            listOf(connectionRow, destinationRow, emulateRow, functionRow, rateRow).forEach { b.llDecisions.addView(it.root) }
            filledActions.forEach { b.llActions.addView(it) }
            b.llActions.addView(outlinedAction)
        }

        private fun decisionRow(
            @StringRes labelRes: Int?,
        ): BindingDecisionRowBinding {
            val row = BindingDecisionRowBinding.inflate(inflater, b.llDecisions, false)
            if (labelRes != null) row.tvRowLabel.setText(labelRes) else row.tvRowLabel.visibility = View.GONE
            return row
        }

        fun bind(row: Row) {
            val slot = row.slot
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL

            b.ivControllerType.setImageResource(
                if (isVirtual) R.drawable.ic_gamepad_virtual else R.drawable.ic_gamepad,
            )
            b.tvControllerName.text = slot.name
            bindBattery(slot.battery)

            val edge = edgeOf(slot)
            if (edge != EdgeState.UNSTEADY) dismissedUnsteady.remove(slot.id)
            val showEdge =
                edge != EdgeState.NONE && !(edge == EdgeState.UNSTEADY && slot.id in dismissedUnsteady)
            b.root.alpha = if (!showEdge && slot.isDisconnecting) 0.5f else 1f

            if (slot.boundStatus == null || slot.boundConnectionId == null) {
                bindUnbound(row)
            } else {
                bindBound(row, slot.boundStatus)
            }
            bindRates(row)
            bindActions(row)
            bindEdge(if (showEdge) edge else EdgeState.NONE, row)
        }

        private fun bindBound(
            row: Row,
            bound: ConnectionSummary,
        ) {
            connectionRow.root.visibility = View.VISIBLE
            connectionPills.bind(connectionSpecs(row, bound.kind))

            destinationRow.root.visibility = View.VISIBLE
            showDestination(bound.label)

            val emulate = typePillLabel(row, bound)
            if (emulate != null) {
                emulateRow.root.visibility = View.VISIBLE
                emulatePills.bind(listOf(PillSpec(emulate, null, PillTone.FACT)))
            } else {
                emulateRow.root.visibility = View.GONE
            }

            functionRow.root.visibility = View.VISIBLE
            bindFunctionPills(functionSpecs(row, bound))
        }

        private fun bindUnbound(row: Row) {
            // Unbound shows the connection without a mode chip; Direct/Standard is only chosen at bind time.
            connectionRow.root.visibility = View.VISIBLE
            connectionPills.bind(connectionSpecs(row, kind = null))
            destinationRow.root.visibility = View.VISIBLE
            showDestination(null)
            emulateRow.root.visibility = View.GONE
            functionRow.root.visibility = View.GONE
        }

        private fun showDestination(monoText: String?) {
            if (monoText != null) {
                destinationMono.root.text = monoText
                destinationMono.root.visibility = View.VISIBLE
                destinationNotBound.root.visibility = View.GONE
            } else {
                destinationMono.root.visibility = View.GONE
                destinationNotBound.root.visibility = View.VISIBLE
            }
        }

        private fun connectionSpecs(
            row: Row,
            kind: ConnectionKind?,
        ): List<PillSpec> {
            val card = row.pathCard
            val virtual = row.slot.inputType == SlotInputType.VIRTUAL
            val isUsb = !virtual && card?.transport == Transport.Usb
            val isBt = !virtual && card?.transport == Transport.Bluetooth
            val (label, icon) =
                when {
                    virtual -> R.string.binding_link_onscreen to R.drawable.ic_gamepad_virtual
                    isBt -> R.string.binding_link_bluetooth to R.drawable.ic_bluetooth
                    else -> R.string.binding_link_usb to R.drawable.ic_usb
                }
            val specs = mutableListOf(PillSpec(ctx.getString(label), icon, PillTone.FACT))
            // The Direct/Standard mode chip only applies once a USB controller is on a known path.
            if (isUsb && kind != null) specs.add(usbModeSpec(card))
            return specs
        }

        private fun usbModeSpec(card: PathCard): PillSpec =
            if (card.currentMode == InputPathMode.Direct) {
                val tone = if (card.risk == PathRisk.GuessedLayout) PillTone.WARN else PillTone.ON
                PillSpec(ctx.getString(R.string.binding_mode_direct), R.drawable.ic_bolt, tone)
            } else {
                PillSpec(ctx.getString(R.string.binding_mode_standard), R.drawable.ic_cable, PillTone.CAP)
            }

        private fun typePillLabel(
            row: Row,
            bound: ConnectionSummary,
        ): String? =
            when (bound.kind) {
                ConnectionKind.SATELLITE -> {
                    val type = bound.satelliteControllerTypes[row.slot.id] ?: CONTROLLER_TYPE_XBOX
                    ctx.getString(if (type == CONTROLLER_TYPE_PLAYSTATION) R.string.picker_type_playstation else R.string.picker_type_xbox)
                }
                ConnectionKind.BLUETOOTH -> bound.btProfile
            }

        private fun bindFunctionPills(specs: List<PillSpec>) {
            if (specs.isEmpty()) {
                functionPills.hideAll()
                functionNone.root.visibility = View.VISIBLE
            } else {
                functionNone.root.visibility = View.GONE
                functionPills.bind(specs)
            }
        }

        // Reports the configured (not live-gated) routing: motion only carries on a Satellite host
        // emulating PlayStation; touchpad only on a Satellite host.
        private fun functionSpecs(
            row: Row,
            bound: ConnectionSummary,
        ): List<PillSpec> {
            val specs = mutableListOf<PillSpec>()
            val card = row.pathCard
            val rumblePresent =
                card != null &&
                    (if (card.currentMode == InputPathMode.Direct) card.direct.rumble else card.standard.rumble)
            if (rumblePresent) {
                specs.add(PillSpec(funcValue(R.string.binding_func_rumble, R.string.binding_state_on), R.drawable.ic_rumble, PillTone.ON))
            }

            val type = bound.satelliteControllerTypes[row.slot.id] ?: CONTROLLER_TYPE_XBOX
            val motionAvailable =
                row.motionCap.inputOk(Feature.MOTION) &&
                    bound.kind == ConnectionKind.SATELLITE &&
                    type == CONTROLLER_TYPE_PLAYSTATION
            if (motionAvailable) {
                val on = row.motionCap.userWants(Feature.MOTION)
                val state = if (on) R.string.binding_state_on else R.string.binding_state_off
                val tone = if (on) PillTone.ON else PillTone.OFF
                specs.add(PillSpec(funcValue(R.string.binding_func_motion, state), R.drawable.ic_motion, tone))
            }

            if (bound.kind == ConnectionKind.SATELLITE) {
                val mode = row.touchpad?.mode ?: TouchpadModeValue.OFF
                val valueRes =
                    when (mode) {
                        TouchpadModeValue.DS4 -> R.string.touchpad_mode_pad
                        TouchpadModeValue.MOUSE -> R.string.touchpad_mode_mouse
                        else -> R.string.touchpad_mode_off
                    }
                val label =
                    ctx.getString(
                        R.string.binding_func_value,
                        ctx.getString(R.string.binding_func_touchpad),
                        ctx.getString(valueRes),
                    )
                val icon = if (mode == TouchpadModeValue.MOUSE) R.drawable.ic_mouse else R.drawable.ic_touchpad
                val tone = if (mode == TouchpadModeValue.OFF) PillTone.OFF else PillTone.ON
                specs.add(PillSpec(label, icon, tone))
            }
            return specs
        }

        private fun funcValue(
            nameRes: Int,
            valueRes: Int,
        ): String = ctx.getString(R.string.binding_func_value, ctx.getString(nameRes), ctx.getString(valueRes))

        private fun bindBattery(battery: BatteryUi?) {
            if (battery == null) {
                b.tvBattery.visibility = View.GONE
                return
            }
            b.tvBattery.visibility = View.VISIBLE
            val glyph = setStartCompoundDrawable(b.tvBattery, batteryIcon(battery), R.dimen.icon_battery)
            (glyph as? Animatable)?.start()
            b.tvBattery.text =
                battery.level?.let { ctx.getString(R.string.battery_percent, it) }
                    ?: ctx.getString(R.string.battery_unknown_level)
            val colorRes = if (battery.isLow) R.color.colorError else R.color.colorMuted
            b.tvBattery.setTextColor(ctx.getColor(colorRes))
            b.tvBattery.contentDescription = batteryDescription(battery)
        }

        private fun batteryIcon(battery: BatteryUi): Int {
            if (battery.charging) return R.drawable.ic_battery_charging
            val level = battery.level ?: return R.drawable.ic_battery
            return when {
                level <= 0 -> R.drawable.ic_battery_empty
                level >= BATTERY_FULL_FLOOR -> R.drawable.ic_battery_full
                level >= BATTERY_HIGH_FLOOR -> R.drawable.ic_battery_high
                level >= BATTERY_MID_FLOOR -> R.drawable.ic_battery_mid
                level >= BatteryUi.LOW_THRESHOLD -> R.drawable.ic_battery_low
                else -> R.drawable.ic_battery_critical
            }
        }

        private fun batteryDescription(battery: BatteryUi): String {
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

        // The measurement line exists exactly on bound cards and always renders every pill the
        // slot can have (value, pending, or Off), so a bound card's height never changes as
        // measurements arrive. A physical slot measures screen, gyro, and controller; the
        // virtual slot has no controller, so it measures screen and gyro.
        private fun bindRates(row: Row) {
            val slot = row.slot
            if (slot.boundStatus == null || slot.boundConnectionId == null) {
                rateRow.root.visibility = View.GONE
                return
            }
            rateRow.root.visibility = View.VISIBLE
            ratePills.bind(rateSpecs(row))
        }

        // Direct streams reports continuously, so the live window is the measurement; routed
        // paths (USB Standard, Bluetooth) and touch only deliver events while the user is
        // pressing, so their peak window approximates the delivery rate and is shown with "~".
        // Direct's measured rates render in the success tone to set them apart.
        private fun rateSpecs(row: Row): List<PillSpec> {
            val direct = row.pathCard?.currentMode == InputPathMode.Direct
            val measuredTone = if (direct) PillTone.SUCCESS else PillTone.FACT
            val specs = mutableListOf(screenRatePill(row), gyroRatePill(row, measuredTone))
            if (row.slot.inputType == SlotInputType.PHYSICAL) {
                specs.add(controllerRatePill(row, direct, measuredTone))
            }
            return specs
        }

        private fun screenRatePill(row: Row): PillSpec {
            val computes =
                screenRateUserFacingOn(
                    inputType = row.slot.inputType,
                    boundKind = row.slot.boundStatus?.kind,
                    touchpad = row.touchpad,
                )
            return when {
                !computes ->
                    PillSpec(ctx.getString(R.string.binding_state_off), R.drawable.ic_touchpad, PillTone.OFF)
                row.screenPeakHz > 0 ->
                    PillSpec(ctx.getString(R.string.binding_rate_hz_peak, row.screenPeakHz), R.drawable.ic_touchpad, PillTone.FACT)
                else ->
                    PillSpec(ctx.getString(R.string.binding_rate_pending), R.drawable.ic_touchpad, PillTone.CAP)
            }
        }

        private fun gyroRatePill(
            row: Row,
            measuredTone: PillTone,
        ): PillSpec {
            val gyroHz = row.inputRates?.gyroHz ?: 0
            return when {
                !motionRateUserFacingOn(row.motionCap, row.slot.boundStatus) ->
                    PillSpec(ctx.getString(R.string.binding_state_off), R.drawable.ic_motion, PillTone.OFF)
                gyroHz > 0 ->
                    PillSpec(ctx.getString(R.string.binding_rate_hz, gyroHz), R.drawable.ic_motion, measuredTone)
                else ->
                    PillSpec(ctx.getString(R.string.binding_rate_pending), R.drawable.ic_motion, PillTone.CAP)
            }
        }

        private fun controllerRatePill(
            row: Row,
            direct: Boolean,
            measuredTone: PillTone,
        ): PillSpec {
            val hz = row.inputRates?.let { controllerRateText(it, direct) }
            return if (hz != null) {
                PillSpec(hz, R.drawable.ic_gamepad, measuredTone)
            } else {
                PillSpec(ctx.getString(R.string.binding_rate_pending), R.drawable.ic_gamepad, PillTone.CAP)
            }
        }

        private fun controllerRateText(
            rates: SlotInputRates,
            direct: Boolean,
        ): String? =
            when {
                direct && rates.controllerHz > 0 -> ctx.getString(R.string.binding_rate_hz, rates.controllerHz)
                rates.controllerPeakHz > 0 -> ctx.getString(R.string.binding_rate_hz_peak, rates.controllerPeakHz)
                else -> null
            }

        private fun bindActions(row: Row) {
            val actions = computeActions(row)
            filledActions.forEach { it.visibility = View.GONE }
            outlinedAction.visibility = View.GONE
            var filledIndex = 0
            for (action in actions) {
                val btn = if (action.outlined) outlinedAction else filledActions[filledIndex++]
                btn.visibility = View.VISIBLE
                btn.text = action.label
                btn.setIconResource(action.icon)
                btn.setOnClickListener { dispatch(action.kind, row.slot.id) }
            }
        }

        private fun computeActions(row: Row): List<CardAction> {
            val slot = row.slot
            val bound = slot.boundStatus
            if (bound == null || slot.boundConnectionId == null) {
                return listOf(
                    if (row.connections.isEmpty()) {
                        CardAction(
                            R.drawable.ic_satellite,
                            ctx.getString(R.string.binding_action_find_hosts),
                            outlined = false,
                            kind = ActionKind.FIND_HOSTS,
                        )
                    } else {
                        CardAction(
                            R.drawable.ic_tune,
                            ctx.getString(R.string.binding_action_configure),
                            outlined = true,
                            kind = ActionKind.CONFIGURE,
                        )
                    },
                )
            }
            val actions = mutableListOf<CardAction>()
            val connected = bound.live == LinkState.Connected
            if (slot.inputType == SlotInputType.VIRTUAL && connected) {
                actions +=
                    CardAction(
                        R.drawable.ic_open_gamepad,
                        ctx.getString(R.string.action_open_gamepad),
                        outlined = false,
                        kind = ActionKind.GAMEPAD,
                    )
            }
            // Openable = mode on AND the phone screen is the touch source. A USB-direct pad
            // streaming its own trackpad gets no overlay: two producers would fight over the
            // slot's single MSG_TOUCHPAD stream.
            if (bound.kind == ConnectionKind.SATELLITE && connected && row.touchpad?.openable == true) {
                actions +=
                    CardAction(
                        R.drawable.ic_open_touchpad,
                        ctx.getString(R.string.action_open_touchpad),
                        outlined = false,
                        kind = ActionKind.TOUCHPAD,
                    )
            }
            actions +=
                CardAction(
                    R.drawable.ic_tune,
                    ctx.getString(R.string.binding_action_configure),
                    outlined = true,
                    kind = ActionKind.CONFIGURE,
                )
            return actions
        }

        private fun dispatch(
            kind: ActionKind,
            slotId: String,
        ) {
            when (kind) {
                ActionKind.GAMEPAD -> listener.onOpenGamepad(slotId)
                ActionKind.TOUCHPAD -> listener.onOpenTouchpad(slotId)
                ActionKind.CONFIGURE -> listener.onConfigure(slotId)
                ActionKind.FIND_HOSTS -> listener.onManageDestinations()
            }
        }

        private fun edgeOf(slot: ControllerSlot): EdgeState {
            val bound = slot.boundStatus
            if (bound == null || slot.boundConnectionId == null) return EdgeState.NONE
            if (slot.isDisconnecting) return EdgeState.INPUT_LOST
            return when (bound.live) {
                LinkState.Unstable -> EdgeState.UNSTEADY
                LinkState.Connected -> EdgeState.NONE
                // Connecting (incl. a global reconnect in flight) keeps showing "lost" so the badge doesn't flicker off.
                else -> EdgeState.HOST_LOST
            }
        }

        private fun bindEdge(
            edge: EdgeState,
            row: Row,
        ) {
            b.edgeOverlay.visibility = if (edge == EdgeState.NONE) View.GONE else View.VISIBLE
            if (edge == EdgeState.NONE) return
            val slot = row.slot
            b.pbEdgeReconnect.visibility = View.GONE
            val accent =
                when (edge) {
                    EdgeState.HOST_LOST -> {
                        b.ivEdgeIcon.setImageResource(R.drawable.ic_error)
                        b.tvEdgeTitle.setText(R.string.binding_edge_host_lost_title)
                        b.tvEdgeDetail.text =
                            ctx.getString(R.string.binding_edge_host_lost_detail, slot.boundStatus?.label ?: "")
                        b.edgeCountdownRow.visibility = View.GONE
                        if (row.connections.any { it.live == LinkState.Connecting }) {
                            hideEdgePrimary()
                            b.pbEdgeReconnect.visibility = View.VISIBLE
                        } else {
                            setEdgePrimary(R.drawable.ic_refresh, R.string.binding_edge_action_reconnect) { listener.onReconnect(slot.id) }
                        }
                        setEdgeSecondary(R.string.binding_action_configure) { listener.onConfigure(slot.id) }
                        R.color.colorError
                    }
                    EdgeState.INPUT_LOST -> {
                        b.ivEdgeIcon.setImageResource(R.drawable.ic_usb)
                        b.tvEdgeTitle.setText(R.string.binding_edge_input_lost_title)
                        b.tvEdgeDetail.setText(R.string.binding_edge_input_lost_detail)
                        b.edgeCountdownRow.visibility = View.VISIBLE
                        b.tvEdgeCountdown.text = slot.disconnectTimeLeft.toString()
                        setEdgePrimary(R.drawable.ic_link_off, R.string.action_unbind) { listener.onUnbind(slot.id) }
                        hideEdgeSecondary()
                        R.color.colorWarning
                    }
                    EdgeState.UNSTEADY -> {
                        b.ivEdgeIcon.setImageResource(R.drawable.ic_warning)
                        b.tvEdgeTitle.setText(R.string.binding_edge_unsteady_title)
                        b.tvEdgeDetail.setText(R.string.binding_edge_unsteady_detail)
                        b.edgeCountdownRow.visibility = View.GONE
                        hideEdgePrimary()
                        setEdgeSecondary(R.string.binding_edge_action_dismiss) {
                            dismissedUnsteady.add(slot.id)
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                        }
                        R.color.colorWarning
                    }
                    EdgeState.NONE -> R.color.colorWarning
                }
            b.ivEdgeIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(accent))
        }

        private fun setEdgePrimary(
            @DrawableRes icon: Int,
            labelRes: Int,
            onClick: () -> Unit,
        ) {
            b.btnEdgePrimary.visibility = View.VISIBLE
            b.btnEdgePrimary.setIconResource(icon)
            b.btnEdgePrimary.setText(labelRes)
            b.btnEdgePrimary.setOnClickListener { onClick() }
        }

        private fun hideEdgePrimary() {
            b.btnEdgePrimary.visibility = View.GONE
        }

        private fun setEdgeSecondary(
            labelRes: Int,
            onClick: () -> Unit,
        ) {
            b.btnEdgeSecondary.visibility = View.VISIBLE
            b.btnEdgeSecondary.setText(labelRes)
            b.btnEdgeSecondary.setOnClickListener { onClick() }
        }

        private fun hideEdgeSecondary() {
            b.btnEdgeSecondary.visibility = View.GONE
        }
    }

    private data class CardAction(
        @DrawableRes val icon: Int,
        val label: String,
        val outlined: Boolean,
        val kind: ActionKind,
    )

    private enum class ActionKind { GAMEPAD, TOUCHPAD, CONFIGURE, FIND_HOSTS }

    private enum class EdgeState { NONE, HOST_LOST, INPUT_LOST, UNSTEADY }

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

private const val MAX_FILLED_ACTIONS = 2

private const val BATTERY_FULL_FLOOR = 90
private const val BATTERY_HIGH_FLOOR = 60
private const val BATTERY_MID_FLOOR = 35

private class PillPool(
    private val container: LinearLayout,
) {
    private val pills = mutableListOf<BindingPillBinding>()

    fun bind(specs: List<PillSpec>) {
        specs.forEachIndexed { i, spec ->
            obtain(i).also { pill ->
                pill.bindPill(spec)
                pill.root.visibility = View.VISIBLE
            }
        }
        for (i in specs.size until pills.size) pills[i].root.visibility = View.GONE
    }

    fun hideAll() {
        for (pill in pills) pill.root.visibility = View.GONE
    }

    private fun obtain(index: Int): BindingPillBinding {
        while (pills.size <= index) {
            pills.add(BindingPillBinding.inflate(LayoutInflater.from(container.context), container, true))
        }
        return pills[index]
    }
}
