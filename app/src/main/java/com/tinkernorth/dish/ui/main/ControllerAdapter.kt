// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.DishProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.databinding.BindingDecisionRowBinding
import com.tinkernorth.dish.databinding.BindingPillBinding
import com.tinkernorth.dish.databinding.BindingValueMonoBinding
import com.tinkernorth.dish.databinding.BindingValueNoneBinding
import com.tinkernorth.dish.databinding.BindingValueNotBoundBinding
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.ui.common.bundledControllerTypeLabelRes
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes

interface SlotActionListener {
    fun onConfigure(slotId: String)

    fun onOpenGamepad(slotId: String)

    fun onOpenTouchpad(slotId: String)

    fun onOpenMouse(slotId: String)

    fun onSwitchToDirect(slotId: String)

    fun onSetupWired(slotId: String)

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

// The badge a bound slot's card can wear; NONE is the quiet default.
internal enum class EdgeState { NONE, HOST_LOST, INPUT_LOST, UNSTEADY }

// A Moonlight host is never "lost": there is no live link to lose, only remembered trust,
// and the session is started by the binding itself. Its state is reported in the binding
// screen where the actions that recover it live, so the dashboard stays quiet.
internal fun slotEdgeState(slot: ControllerSlot): EdgeState {
    val bound = slot.boundStatus
    if (bound == null || slot.boundConnectionId == null) return EdgeState.NONE
    if (slot.isDisconnecting) return EdgeState.INPUT_LOST
    if (bound.kind == ConnectionKind.MOONLIGHT) return EdgeState.NONE
    return when (bound.live) {
        LinkState.Unstable -> EdgeState.UNSTEADY
        LinkState.Connected -> EdgeState.NONE
        // Connecting (incl. a global reconnect in flight) keeps showing "lost" so the badge doesn't flicker off.
        else -> EdgeState.HOST_LOST
    }
}

// A Moonlight host is always offered. Its session is started BY the binding, so requiring a
// live link before it can be picked is circular: it can never be live until something binds to
// it, and nothing can bind to it until it is live.
internal fun connectionsVisibleInPicker(
    all: List<ConnectionSummary>,
    boundConnectionId: String?,
): List<ConnectionSummary> =
    all.filter {
        it.live.isAvailableForPicker() || it.kind == ConnectionKind.MOONLIGHT || it.id == boundConnectionId
    }

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
// gamepad for the virtual slot, or one of the slot's phone pointer surfaces (a pad streaming
// its own trackpad has neither). Outside those states the card's screen rate reads Off.
internal fun screenRateUserFacingOn(
    inputType: SlotInputType,
    boundKind: ConnectionKind?,
    pointer: PointerSlotUi?,
): Boolean =
    inputType == SlotInputType.VIRTUAL ||
        (boundKind == ConnectionKind.SATELLITE && pointer?.anyOpenable == true)

class ControllerAdapter(
    private val listener: SlotActionListener,
) : ListAdapter<ControllerAdapter.Row, ControllerAdapter.VH>(Diff) {
    private val dismissedUnsteady = mutableSetOf<String>()

    data class Row(
        val slot: ControllerSlot,
        val connections: List<ConnectionSummary>,
        val motionCap: SlotCapabilities = SlotCapabilities.NONE,
        val pointer: PointerSlotUi? = null,
        val pathCard: PathCard? = null,
        val inputRates: SlotInputRates? = null,
        val screenPeakHz: Int = 0,
        val hostCompat: DishProtocol.Compat = DishProtocol.Compat.UNKNOWN,
    )

    @Suppress("LongParameterList")
    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
        motionCapabilities: Map<String, SlotCapabilities> = emptyMap(),
        pointerBySlot: Map<String, PointerSlotUi> = emptyMap(),
        pathCards: Map<String, PathCard> = emptyMap(),
        inputRates: Map<String, SlotInputRates> = emptyMap(),
        screenPeakHz: Int = 0,
        hostCompat: Map<String, DishProtocol.Compat> = emptyMap(),
    ) {
        submitList(
            slots.map { slot ->
                Row(
                    slot = slot,
                    connections = connections,
                    motionCap = motionCapabilities[slot.id] ?: SlotCapabilities.NONE,
                    pointer = pointerBySlot[slot.id],
                    pathCard = pathCards[slot.id],
                    inputRates = inputRates[slot.id],
                    screenPeakHz = screenPeakHz,
                    hostCompat = slot.boundConnectionId?.let { hostCompat[it] } ?: DishProtocol.Compat.UNKNOWN,
                )
            },
        )
    }

    inner class VH(
        private val b: ItemControllerBinding,
        @LayoutRes actionsLayoutRes: Int,
    ) : RecyclerView.ViewHolder(b.root) {
        private val ctx: Context get() = b.root.context
        private val inflater: LayoutInflater get() = LayoutInflater.from(ctx)

        private val connectionRow = decisionRow(R.string.binding_label_connection)
        private val destinationRow = decisionRow(R.string.binding_label_destination)

        // The compat chip needs the full value width on its own line: squeezed beside
        // the host name it would wrap into a column and blow the card's height.
        private val compatRow = decisionRow(null)
        private val emulateRow = decisionRow(R.string.binding_label_emulate)
        private val functionRow = decisionRow(null)
        private val rateRow = decisionRow(null)

        private val connectionPills = PillPool(connectionRow.valueContainer)
        private val compatPills = PillPool(compatRow.valueContainer)
        private val emulatePills = PillPool(emulateRow.valueContainer)
        private val functionPills = PillPool(functionRow.valueContainer)
        private val ratePills = PillPool(rateRow.valueContainer)

        init {
            // Fixed line budgets keep every card the same height regardless of
            // content; the function row alone gets two lines because the
            // protocol-2 feedback surfaces can put up to eight chips on it.
            listOf(connectionRow, compatRow, emulateRow, rateRow).forEach {
                it.valueContainer.startAligned = true
                it.valueContainer.fixedLineCount = 1
            }
            functionRow.valueContainer.startAligned = true
            functionRow.valueContainer.fixedLineCount = 2
        }

        private val destinationMono = BindingValueMonoBinding.inflate(inflater, destinationRow.valueContainer, true)
        private val destinationNotBound = BindingValueNotBoundBinding.inflate(inflater, destinationRow.valueContainer, true)
        private val functionNone = BindingValueNoneBinding.inflate(inflater, functionRow.valueContainer, true)

        private val filledActions: List<MaterialButton>
        private val outlinedAction: MaterialButton?

        init {
            listOf(connectionRow, destinationRow, compatRow, emulateRow, functionRow, rateRow)
                .forEach { b.llDecisions.addView(it.root) }
            inflater.inflate(actionsLayoutRes, b.llActions, true)
            filledActions =
                listOfNotNull(
                    b.llActions.findViewById(R.id.btnCardAction1),
                    b.llActions.findViewById(R.id.btnCardAction2),
                    b.llActions.findViewById(R.id.btnCardAction3),
                    b.llActions.findViewById(R.id.btnCardAction4),
                )
            outlinedAction = b.llActions.findViewById(R.id.btnCardActionOutlined)
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

            val edge = slotEdgeState(slot)
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
            connectionPills.bind(connectionSpecs(row))

            destinationRow.root.visibility = View.VISIBLE
            showDestination(bound.label)
            val compatSpecs = listOfNotNull(compatPillSpec(ctx, row.hostCompat))
            compatRow.root.visibility = if (compatSpecs.isEmpty()) View.GONE else View.VISIBLE
            compatPills.bind(compatSpecs)

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
            connectionRow.root.visibility = View.VISIBLE
            connectionPills.bind(connectionSpecs(row))
            destinationRow.root.visibility = View.VISIBLE
            showDestination(null)
            compatRow.root.visibility = View.GONE
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

        private fun connectionSpecs(row: Row): List<PillSpec> {
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
            if (isUsb && card != null) specs.add(usbModeSpec(card))
            if (isBt && card?.wiredSwitchAvailable == true) {
                specs.add(PillSpec(ctx.getString(R.string.binding_usb_available), R.drawable.ic_usb, PillTone.WARN))
            }
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
                    ctx.getString(bundledControllerTypeLabelRes(type))
                }
                ConnectionKind.BLUETOOTH -> bound.btProfile
                // A Moonlight host has its own type table; its ids overlap the catalog's, so
                // the label comes from the Moonlight mapper and never the bundled one.
                ConnectionKind.MOONLIGHT -> {
                    val stored = bound.satelliteControllerTypes[row.slot.id]
                    ctx.getString(moonlightTypeLabelRes(MoonlightEmulatedType.fromStored(stored ?: MoonlightEmulatedType.AUTO)))
                }
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
        // emulating a motion-bearing type; touchpad only on a Satellite host.
        private fun functionSpecs(
            row: Row,
            bound: ConnectionSummary,
        ): List<PillSpec> {
            if (inputFunctionsUnknown(row.pathCard)) return unknownFunctionSpecs(row)
            val specs = mutableListOf<PillSpec>()
            val card = row.pathCard
            val rumblePresent =
                card != null &&
                    (if (card.currentMode == InputPathMode.Direct) card.direct.rumble else card.standard.rumble)
            if (rumblePresent) {
                specs.add(PillSpec(ctx.getString(R.string.binding_func_rumble), R.drawable.ic_rumble, PillTone.ON))
            }

            // Motion streams to a Satellite and, since protocol 2 shipped the
            // Moonlight telemetry, to a Moonlight host too (its type layer gates
            // which emulated pads carry it); Bluetooth stays gamepad-only.
            val motionAvailable =
                row.motionCap.inputOk(Feature.MOTION) &&
                    bound.kind != ConnectionKind.BLUETOOTH &&
                    row.motionCap.typeOk(Feature.MOTION)
            if (motionAvailable) {
                val on = row.motionCap.userWants(Feature.MOTION)
                val tone = if (on) PillTone.ON else PillTone.OFF
                specs.add(PillSpec(ctx.getString(R.string.binding_func_motion), R.drawable.ic_motion, tone))
            }

            when (bound.kind) {
                ConnectionKind.SATELLITE -> specs.addAll(pointerFuncFacts(row).map(::pointerFactPill))
                ConnectionKind.MOONLIGHT -> specs.addAll(moonlightPointerFacts(row).map(::pointerFactPill))
                ConnectionKind.BLUETOOTH -> Unit
            }
            specs.addAll(feedbackFuncFacts(row.motionCap).map(::feedbackFactPill))
            return specs
        }

        private fun unknownFunctionSpecs(row: Row): List<PillSpec> {
            val specs =
                mutableListOf(
                    unknownFuncPill(R.string.binding_func_rumble, R.drawable.ic_rumble),
                    unknownFuncPill(R.string.binding_func_gyro, R.drawable.ic_motion),
                    unknownFuncPill(R.string.binding_func_touchpad, R.drawable.ic_touchpad),
                )
            if (row.pointer?.mouseOpenable == true) specs.add(pointerFactPill(PointerPillFact.MOUSE_READY))
            return specs
        }

        private fun unknownFuncPill(
            @StringRes label: Int,
            @DrawableRes icon: Int,
        ): PillSpec =
            PillSpec(
                ctx.getString(R.string.binding_func_value, ctx.getString(label), ctx.getString(R.string.binding_state_unknown)),
                icon,
                PillTone.OFF,
            )

        private fun feedbackFactPill(fact: FeedbackPillFact): PillSpec =
            when (fact) {
                FeedbackPillFact.TRIGGER_RUMBLE ->
                    PillSpec(ctx.getString(R.string.setup_cap_trigger_rumble), R.drawable.ic_trigger_rumble, PillTone.CAP)
                FeedbackPillFact.LIGHTBAR ->
                    PillSpec(ctx.getString(R.string.setup_cap_lightbar), R.drawable.ic_lightbar, PillTone.CAP)
                FeedbackPillFact.TRIGGER_EFFECTS ->
                    PillSpec(ctx.getString(R.string.setup_cap_trigger_effects), R.drawable.ic_trigger_effects, PillTone.CAP)
                FeedbackPillFact.PLAYER_LEDS ->
                    PillSpec(ctx.getString(R.string.setup_cap_player_leds), R.drawable.ic_player_leds, PillTone.CAP)
            }

        private fun pointerFactPill(fact: PointerPillFact): PillSpec =
            when (fact) {
                PointerPillFact.PAD_NEEDS_DIRECT ->
                    PillSpec(ctx.getString(R.string.touchpad_needs_direct), R.drawable.ic_touchpad, PillTone.WARN)
                PointerPillFact.PAD_ON ->
                    PillSpec(ctx.getString(R.string.binding_func_touchpad), R.drawable.ic_touchpad, PillTone.ON)
                PointerPillFact.PAD_OFF ->
                    PillSpec(ctx.getString(R.string.binding_func_touchpad), R.drawable.ic_touchpad, PillTone.OFF)
                PointerPillFact.MOUSE_READY ->
                    PillSpec(ctx.getString(R.string.binding_func_mouse), R.drawable.ic_mouse, PillTone.CAP)
            }

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
                    pointer = row.pointer,
                )
            return when {
                !computes ->
                    PillSpec(ctx.getString(R.string.binding_func_touchpad), R.drawable.ic_touchpad, PillTone.OFF)
                row.screenPeakHz > 0 ->
                    PillSpec(ctx.getString(R.string.binding_rate_hz_peak, row.screenPeakHz), R.drawable.ic_touchpad, PillTone.FACT)
                else ->
                    PillSpec(ctx.getString(R.string.binding_func_touchpad), R.drawable.ic_touchpad, PillTone.CAP)
            }
        }

        private fun gyroRatePill(
            row: Row,
            measuredTone: PillTone,
        ): PillSpec {
            val gyroHz = row.inputRates?.gyroHz ?: 0
            return when {
                !motionRateUserFacingOn(row.motionCap, row.slot.boundStatus) ->
                    PillSpec(ctx.getString(R.string.binding_func_gyro), R.drawable.ic_motion, PillTone.OFF)
                gyroHz > 0 ->
                    PillSpec(ctx.getString(R.string.binding_rate_hz, gyroHz), R.drawable.ic_motion, measuredTone)
                else ->
                    PillSpec(ctx.getString(R.string.binding_func_gyro), R.drawable.ic_motion, PillTone.CAP)
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
                PillSpec(ctx.getString(R.string.setup_cfg_flow_controller), R.drawable.ic_gamepad, PillTone.CAP)
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
            val actions = computeCardActions(row)
            actions.filled.forEachIndexed { index, spec -> bindActionButton(filledActions[index], spec, row.slot.id) }
            val outlinedSpec = actions.outlined
            if (outlinedSpec != null) outlinedAction?.let { bindActionButton(it, outlinedSpec, row.slot.id) }
        }

        private fun bindActionButton(
            button: MaterialButton,
            spec: CardActionSpec,
            slotId: String,
        ) {
            button.setIconResource(spec.icon)
            button.setText(spec.label)
            button.setOnClickListener { dispatch(spec.kind, slotId) }
        }

        private fun dispatch(
            kind: CardActionKind,
            slotId: String,
        ) {
            when (kind) {
                CardActionKind.GAMEPAD -> listener.onOpenGamepad(slotId)
                CardActionKind.TOUCHPAD -> listener.onOpenTouchpad(slotId)
                CardActionKind.MOUSE -> listener.onOpenMouse(slotId)
                CardActionKind.SWITCH_DIRECT -> listener.onSwitchToDirect(slotId)
                CardActionKind.SETUP_WIRED -> listener.onSetupWired(slotId)
                CardActionKind.CONFIGURE -> listener.onConfigure(slotId)
                CardActionKind.FIND_HOSTS -> listener.onManageDestinations()
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

    override fun getItemViewType(position: Int): Int = computeCardActions(getItem(position)).viewType

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ) = VH(
        ItemControllerBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        cardActionsLayoutFor(viewType),
    )

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

internal enum class CardActionKind { GAMEPAD, TOUCHPAD, MOUSE, SWITCH_DIRECT, SETUP_WIRED, CONFIGURE, FIND_HOSTS }

// What the card's function row says about the slot's pointer surfaces, kept pure so the
// facts stay testable: the pad surface reports its declared routing (or the Direct nudge
// that unlocks it), and the mouse rides as an on-demand chip wherever its surface can open.
internal enum class PointerPillFact { PAD_NEEDS_DIRECT, PAD_ON, PAD_OFF, MOUSE_READY }

// Direct on an unrecognized model reads a guessed layout, so what the pad supports is not known.
internal fun inputFunctionsUnknown(card: PathCard?): Boolean =
    card != null && card.currentMode == InputPathMode.Direct && card.risk == PathRisk.GuessedLayout

internal fun pointerFuncFacts(row: ControllerAdapter.Row): List<PointerPillFact> =
    buildList {
        when {
            row.pathCard?.suggestDirectForTouch == true -> add(PointerPillFact.PAD_NEEDS_DIRECT)
            row.pointer?.mode == TouchpadModeValue.DS4 -> add(PointerPillFact.PAD_ON)
            row.motionCap.typeOk(Feature.TOUCHPAD) -> add(PointerPillFact.PAD_OFF)
        }
        if (row.pointer?.mouseOpenable == true) add(PointerPillFact.MOUSE_READY)
    }

internal data class CardActionSpec(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val kind: CardActionKind,
)

// The action row's shape IS the RecyclerView view type: each (filled count, outlined) pair
// maps to a layout holding exactly those buttons, so binding never shows or hides one.
internal data class CardActions(
    val filled: List<CardActionSpec>,
    val outlined: CardActionSpec?,
) {
    val viewType: Int get() = filled.size * 2 + if (outlined != null) 1 else 0
}

@LayoutRes
internal fun cardActionsLayoutFor(viewType: Int): Int =
    when (viewType) {
        VIEW_TYPE_O1 -> R.layout.binding_card_actions_o1
        VIEW_TYPE_F1 -> R.layout.binding_card_actions_f1
        VIEW_TYPE_F1_O1 -> R.layout.binding_card_actions_f1_o1
        VIEW_TYPE_F2 -> R.layout.binding_card_actions_f2
        VIEW_TYPE_F2_O1 -> R.layout.binding_card_actions_f2_o1
        VIEW_TYPE_F3_O1 -> R.layout.binding_card_actions_f3_o1
        VIEW_TYPE_F4_O1 -> R.layout.binding_card_actions_f4_o1
        else -> error("No card actions layout for view type $viewType")
    }

private const val VIEW_TYPE_O1 = 1
private const val VIEW_TYPE_F1 = 2
private const val VIEW_TYPE_F1_O1 = 3
private const val VIEW_TYPE_F2 = 4
private const val VIEW_TYPE_F2_O1 = 5
private const val VIEW_TYPE_F3_O1 = 7
private const val VIEW_TYPE_F4_O1 = 9

private val CONFIGURE_SPEC =
    CardActionSpec(R.drawable.ic_tune, R.string.binding_action_configure, CardActionKind.CONFIGURE)
private val SETUP_WIRED_SPEC =
    CardActionSpec(R.drawable.ic_usb, R.string.binding_action_use_wired, CardActionKind.SETUP_WIRED)

internal fun computeCardActions(row: ControllerAdapter.Row): CardActions {
    val slot = row.slot
    val bound = slot.boundStatus
    if (bound == null || slot.boundConnectionId == null) {
        val filled = mutableListOf<CardActionSpec>()
        if (row.pathCard?.wiredSwitchAvailable == true) filled += SETUP_WIRED_SPEC
        if (row.connections.isEmpty()) {
            filled += CardActionSpec(R.drawable.ic_satellite, R.string.binding_action_find_hosts, CardActionKind.FIND_HOSTS)
            return CardActions(filled, outlined = null)
        }
        return CardActions(filled, CONFIGURE_SPEC)
    }
    val filled = mutableListOf<CardActionSpec>()
    val connected = bound.live == LinkState.Connected
    val satellite = bound.kind == ConnectionKind.SATELLITE
    val pointerHost = satellite || bound.kind == ConnectionKind.MOONLIGHT
    if (slot.inputType == SlotInputType.VIRTUAL && connected) {
        filled += CardActionSpec(R.drawable.ic_open_gamepad, R.string.action_open_gamepad, CardActionKind.GAMEPAD)
    }
    // A phone pointer surface only exists where the phone is the slot's touch source:
    // a USB-direct pad streaming its own trackpad gets neither button, because two
    // producers would fight over the slot's single MSG_TOUCHPAD stream. The virtual
    // slot never offers the touchpad surface: its trackpad lives inside the pad itself.
    if (satellite && connected && slot.inputType != SlotInputType.VIRTUAL && row.pointer?.touchpadOpenable == true) {
        filled += CardActionSpec(R.drawable.ic_open_touchpad, R.string.action_open_touchpad, CardActionKind.TOUCHPAD)
    }
    if (pointerHost && connected && row.pointer?.mouseOpenable == true) {
        filled += CardActionSpec(R.drawable.ic_mouse, R.string.action_open_mouse, CardActionKind.MOUSE)
    }
    if (satellite && connected && row.pathCard?.suggestDirectForTouch == true) {
        filled += CardActionSpec(R.drawable.ic_bolt, R.string.card_switch_to_direct, CardActionKind.SWITCH_DIRECT)
    }
    if (row.pathCard?.wiredSwitchAvailable == true) filled += SETUP_WIRED_SPEC
    return CardActions(filled, CONFIGURE_SPEC)
}

private const val BATTERY_FULL_FLOOR = 90
private const val BATTERY_HIGH_FLOOR = 60
private const val BATTERY_MID_FLOOR = 35

private class PillPool(
    private val container: ViewGroup,
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
