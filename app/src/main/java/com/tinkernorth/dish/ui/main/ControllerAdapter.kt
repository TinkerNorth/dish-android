// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.databinding.BindingDecisionRowBinding
import com.tinkernorth.dish.databinding.BindingPillBinding
import com.tinkernorth.dish.databinding.BindingValueMonoBinding
import com.tinkernorth.dish.databinding.BindingValueNoneBinding
import com.tinkernorth.dish.databinding.BindingValueNotBoundBinding
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TouchpadModeValue

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

class ControllerAdapter(
    private val listener: SlotActionListener,
) : ListAdapter<ControllerAdapter.Row, ControllerAdapter.VH>(Diff) {
    private val dismissedUnsteady = mutableSetOf<String>()

    data class Row(
        val slot: ControllerSlot,
        val connections: List<ConnectionSummary>,
        val motionCap: MotionCapability = MotionCapability.Off,
        val touchpadModes: Map<String, String> = emptyMap(),
        val pathCard: PathCard? = null,
    )

    fun submitSlots(
        slots: List<ControllerSlot>,
        connections: List<ConnectionSummary>,
        motionCapabilities: Map<String, MotionCapability> = emptyMap(),
        touchpadModes: Map<String, String> = emptyMap(),
        pathCards: Map<String, PathCard> = emptyMap(),
    ) {
        submitList(
            slots.map { slot ->
                Row(
                    slot = slot,
                    connections = connections,
                    motionCap = motionCapabilities[slot.id] ?: MotionCapability.Off,
                    touchpadModes = touchpadModes,
                    pathCard = pathCards[slot.id],
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

        private val connectionPills = PillPool(connectionRow.valueContainer)
        private val emulatePills = PillPool(emulateRow.valueContainer)
        private val functionPills = PillPool(functionRow.valueContainer)

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
            listOf(connectionRow, destinationRow, emulateRow, functionRow).forEach { b.llDecisions.addView(it.root) }
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
            if (isUsb && kind != null && card != null) specs.add(usbModeSpec(card))
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
                row.motionCap.hasGyro &&
                    bound.kind == ConnectionKind.SATELLITE &&
                    type == CONTROLLER_TYPE_PLAYSTATION
            if (motionAvailable) {
                val on = row.motionCap.userEnabled
                val state = if (on) R.string.binding_state_on else R.string.binding_state_off
                val tone = if (on) PillTone.ON else PillTone.OFF
                specs.add(PillSpec(funcValue(R.string.binding_func_motion, state), R.drawable.ic_motion, tone))
            }

            if (bound.kind == ConnectionKind.SATELLITE) {
                val mode = row.touchpadModes[row.slot.boundConnectionId] ?: TouchpadModeValue.OFF
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
            val touchpadMode = row.touchpadModes[slot.boundConnectionId] ?: TouchpadModeValue.OFF
            if (bound.kind == ConnectionKind.SATELLITE && connected && touchpadMode != TouchpadModeValue.OFF) {
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
