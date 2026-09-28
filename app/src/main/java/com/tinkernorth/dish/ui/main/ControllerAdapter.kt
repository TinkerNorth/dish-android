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
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.databinding.BindingDecisionRowBinding
import com.tinkernorth.dish.databinding.BindingPillBinding
import com.tinkernorth.dish.databinding.BindingValueMonoBinding
import com.tinkernorth.dish.databinding.BindingValueNoneBinding
import com.tinkernorth.dish.databinding.BindingValueNotBoundBinding
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import java.util.Locale

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

class ControllerAdapter(
    private val listener: SlotActionListener,
) : ListAdapter<ControllerRow, ControllerAdapter.VH>(ControllerRowDiff()) {
    private val dismissedUnsteady = mutableSetOf<String>()

    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
        motionCapabilities: Map<String, SlotCapabilities> = emptyMap(),
        pointerBySlot: Map<String, PointerSlotUi> = emptyMap(),
        pathCards: Map<String, PathCard> = emptyMap(),
        inputRates: Map<String, SlotInputRates> = emptyMap(),
        screenPeakHz: Int = 0,
        hostCompat: Map<String, DishProtocolCompat> = emptyMap(),
    ) {
        submitList(
            slots.map { slot ->
                ControllerRow(
                    slot = slot,
                    connections = connections,
                    motionCap = motionCapabilities[slot.id] ?: SlotCapabilities.NONE,
                    pointer = pointerBySlot[slot.id],
                    pathCard = pathCards[slot.id],
                    inputRates = inputRates[slot.id],
                    screenPeakHz = screenPeakHz,
                    hostCompat = slot.boundConnectionId?.let { hostCompat[it] } ?: DishProtocolCompat.UNKNOWN,
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

        fun bind(row: ControllerRow) {
            val slot = row.slot
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL

            b.ivControllerType.setImageResource(
                if (isVirtual) R.drawable.ic_gamepad_virtual else R.drawable.ic_gamepad,
            )
            b.tvControllerName.text = slot.name
            bindBattery(slot.battery)

            val edge = slotEdgeState(slot)
            if (edge != EdgeState.UNSTEADY) dismissedUnsteady.remove(slot.id)
            val dismissed = edge == EdgeState.UNSTEADY && slot.id in dismissedUnsteady
            val shownEdge = if (dismissed) EdgeState.NONE else edge
            b.root.alpha = cardAlpha(shownEdge, slot.isDisconnecting)

            val bound = slot.boundStatus
            if (bound == null || slot.boundConnectionId == null) {
                bindUnbound(row)
            } else {
                bindBound(row, bound)
            }
            bindRates(row)
            bindActions(row)
            bindEdge(shownEdge, row)
        }

        private fun bindBound(
            row: ControllerRow,
            bound: ConnectionSummary,
        ) {
            connectionRow.root.visibility = View.VISIBLE
            connectionPills.bind(connectionPillFacts(row).map(::connectionPill))

            destinationRow.root.visibility = View.VISIBLE
            showDestination(bound.label)
            val compatSpecs = listOfNotNull(compatPillSpec(ctx, row.hostCompat))
            compatRow.root.visibility = if (compatSpecs.isEmpty()) View.GONE else View.VISIBLE
            compatPills.bind(compatSpecs)

            bindEmulateRow(emulatePillFor(bound.kind, bound.satelliteControllerTypes[row.slot.id], bound.btProfile))

            functionRow.root.visibility = View.VISIBLE
            bindFunctionPills(functionPillFacts(row, bound).map(::functionPill))
        }

        private fun bindUnbound(row: ControllerRow) {
            connectionRow.root.visibility = View.VISIBLE
            connectionPills.bind(connectionPillFacts(row).map(::connectionPill))
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

        private fun bindEmulateRow(pill: EmulatePill?) {
            if (pill == null) {
                emulateRow.root.visibility = View.GONE
                return
            }
            emulateRow.root.visibility = View.VISIBLE
            emulatePills.bind(listOf(PillSpec(emulateText(pill), null, PillTone.FACT)))
        }

        private fun emulateText(pill: EmulatePill): String =
            when (pill) {
                is EmulatePill.Bundled -> ctx.getString(pill.labelRes)
                is EmulatePill.Profile -> pill.name
            }

        private fun connectionPill(fact: ConnectionPillFact): PillSpec = PillSpec(ctx.getString(fact.labelRes), fact.iconRes, fact.tone)

        private fun bindFunctionPills(specs: List<PillSpec>) {
            if (specs.isEmpty()) {
                functionPills.hideAll()
                functionNone.root.visibility = View.VISIBLE
            } else {
                functionNone.root.visibility = View.GONE
                functionPills.bind(specs)
            }
        }

        private fun functionPill(fact: FunctionPillFact): PillSpec =
            when (fact) {
                FunctionPillFact.RumbleUnknown -> unknownFuncPill(R.string.binding_func_rumble, R.drawable.ic_rumble)
                FunctionPillFact.GyroUnknown -> unknownFuncPill(R.string.binding_func_gyro, R.drawable.ic_motion)
                FunctionPillFact.TouchpadUnknown -> unknownFuncPill(R.string.binding_func_touchpad, R.drawable.ic_touchpad)
                FunctionPillFact.Rumble ->
                    PillSpec(ctx.getString(R.string.binding_func_rumble), R.drawable.ic_rumble, PillTone.ON)
                is FunctionPillFact.Motion ->
                    PillSpec(
                        ctx.getString(R.string.binding_func_motion),
                        R.drawable.ic_motion,
                        if (fact.on) PillTone.ON else PillTone.OFF,
                    )
                is FunctionPillFact.Pointer -> pointerFactPill(fact.fact)
                is FunctionPillFact.Feedback -> feedbackFactPill(fact.fact)
                is FunctionPillFact.Audio -> audioFactPill(fact.fact)
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

        private fun audioFactPill(fact: AudioPillFact): PillSpec =
            when (fact) {
                AudioPillFact.MIC ->
                    PillSpec(ctx.getString(R.string.setup_cap_mic), R.drawable.ic_mic, PillTone.ON)
                AudioPillFact.SPEAKER ->
                    PillSpec(ctx.getString(R.string.setup_cap_speaker), R.drawable.ic_speaker, PillTone.ON)
                AudioPillFact.HAPTICS ->
                    PillSpec(ctx.getString(R.string.setup_cap_haptics), R.drawable.ic_rumble, PillTone.ON)
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
            val glyph = setStartCompoundDrawable(b.tvBattery, batteryIconRes(battery), R.dimen.icon_battery)
            (glyph as? Animatable)?.start()
            b.tvBattery.text = batteryLevelText(battery, R.string.battery_unknown_level)
            val colorRes = if (battery.isLow) R.color.colorError else R.color.colorMuted
            b.tvBattery.setTextColor(ctx.getColor(colorRes))
            b.tvBattery.contentDescription =
                ctx.getString(
                    R.string.battery_desc,
                    batteryLevelText(battery, R.string.battery_desc_level_unknown),
                    ctx.getString(batteryStateRes(battery)),
                )
        }

        private fun batteryLevelText(
            battery: BatteryUi,
            @StringRes unknownRes: Int,
        ): String {
            val level = battery.level ?: return ctx.getString(unknownRes)
            return ctx.getString(R.string.battery_percent, level)
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

        // The measurement line exists exactly on bound cards.
        private fun bindRates(row: ControllerRow) {
            val slot = row.slot
            if (slot.boundStatus == null || slot.boundConnectionId == null) {
                rateRow.root.visibility = View.GONE
                return
            }
            rateRow.root.visibility = View.VISIBLE
            ratePills.bind(ratePillFacts(row).map(::ratePill))
        }

        private fun ratePill(fact: RatePillFact): PillSpec = PillSpec(rateText(fact), fact.glyph.iconRes, fact.tone)

        private fun rateText(fact: RatePillFact): String =
            when (val reading = fact.reading) {
                RateReading.Off, RateReading.Pending -> ctx.getString(fact.glyph.labelRes)
                is RateReading.LiveHz -> ctx.getString(R.string.binding_rate_hz, reading.hz)
                is RateReading.PeakHz -> ctx.getString(R.string.binding_rate_hz_peak, reading.hz)
            }

        private fun bindActions(row: ControllerRow) {
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
            row: ControllerRow,
        ) {
            val card = edgeCardFor(edge, row)
            b.edgeOverlay.visibility = if (card == null) View.GONE else View.VISIBLE
            if (card == null) return
            bindEdgeHeader(card)
            bindEdgePrimary(card.primary, row.slot.id)
            bindEdgeSecondary(card.secondary, row.slot.id)
        }

        private fun bindEdgeHeader(card: EdgeCard) {
            b.ivEdgeIcon.setImageResource(card.iconRes)
            b.ivEdgeIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(card.accentRes))
            b.tvEdgeTitle.setText(card.titleRes)
            b.tvEdgeDetail.text = edgeDetailText(card)
            val countdown = card.countdownSec
            b.edgeCountdownRow.visibility = if (countdown == null) View.GONE else View.VISIBLE
            if (countdown != null) b.tvEdgeCountdown.text = String.format(Locale.getDefault(), "%d", countdown)
        }

        private fun edgeDetailText(card: EdgeCard): String {
            val arg = card.detailArg ?: return ctx.getString(card.detailRes)
            return ctx.getString(card.detailRes, arg)
        }

        private fun bindEdgePrimary(
            primary: EdgePrimary,
            slotId: String,
        ) {
            b.pbEdgeReconnect.visibility = if (primary == EdgePrimary.RECONNECT_PENDING) View.VISIBLE else View.GONE
            when (primary) {
                EdgePrimary.RECONNECT ->
                    setEdgePrimary(R.drawable.ic_refresh, R.string.binding_edge_action_reconnect) { listener.onReconnect(slotId) }
                EdgePrimary.UNBIND ->
                    setEdgePrimary(R.drawable.ic_link_off, R.string.action_unbind) { listener.onUnbind(slotId) }
                EdgePrimary.RECONNECT_PENDING, EdgePrimary.NONE -> hideEdgePrimary()
            }
        }

        private fun bindEdgeSecondary(
            secondary: EdgeSecondary,
            slotId: String,
        ) {
            when (secondary) {
                EdgeSecondary.CONFIGURE -> setEdgeSecondary(R.string.binding_action_configure) { listener.onConfigure(slotId) }
                EdgeSecondary.DISMISS -> setEdgeSecondary(R.string.binding_edge_action_dismiss) { dismissUnsteady(slotId) }
                EdgeSecondary.NONE -> hideEdgeSecondary()
            }
        }

        // Remembered per slot until the link steadies; the row re-binds without its banner.
        private fun dismissUnsteady(slotId: String) {
            dismissedUnsteady.add(slotId)
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        }

        private fun setEdgePrimary(
            @DrawableRes icon: Int,
            @StringRes labelRes: Int,
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
            @StringRes labelRes: Int,
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
}

private class ControllerRowDiff : DiffUtil.ItemCallback<ControllerRow>() {
    override fun areItemsTheSame(
        o: ControllerRow,
        n: ControllerRow,
    ) = o.slot.id == n.slot.id

    override fun areContentsTheSame(
        o: ControllerRow,
        n: ControllerRow,
    ) = o == n
}

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
