// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.databinding.ItemControllerBinding
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TouchpadModeValue

interface SlotActionListener {
    fun onConfigure(slotId: String)

    fun onOpenGamepad(slotId: String)

    fun onOpenTouchpad(slotId: String)

    fun onManageDestinations()
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

        fun bind(row: Row) {
            val slot = row.slot
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL

            b.ivControllerType.setImageResource(
                if (isVirtual) R.drawable.ic_gamepad_virtual else R.drawable.ic_gamepad,
            )
            b.tvControllerName.text = slot.name
            b.root.alpha = if (slot.isDisconnecting) 0.5f else 1f

            b.llDecisions.removeAllViews()
            b.llActions.removeAllViews()

            if (slot.boundStatus == null || slot.boundConnectionId == null) {
                buildUnboundDecisions(row)
            } else {
                buildBoundDecisions(row, slot.boundStatus)
            }
            buildActions(row)
        }

        private fun buildBoundDecisions(
            row: Row,
            bound: ConnectionSummary,
        ) {
            val inner = decisionsContainer()
            inner.addView(decisionRow(ctx.getString(R.string.tz_label_connection), connectionPills(row, bound.kind)))
            inner.addView(decisionRow(ctx.getString(R.string.tz_label_destination), listOf(monoValue(bound.label))))
            typePill(row, bound)?.let {
                inner.addView(decisionRow(ctx.getString(R.string.tz_label_emulate), listOf(it)))
            }
            inner.addView(functionRow(functionPills(row, bound)))
            b.llDecisions.addView(divider())
            b.llDecisions.addView(inner)
        }

        private fun buildUnboundDecisions(row: Row) {
            val inner = decisionsContainer()
            // Unbound shows the connection without a mode chip; Direct/Standard is only chosen at bind time.
            inner.addView(decisionRow(ctx.getString(R.string.tz_label_connection), connectionPills(row, null)))
            inner.addView(decisionRow(ctx.getString(R.string.tz_label_destination), listOf(notBoundValue())))
            b.llDecisions.addView(divider())
            b.llDecisions.addView(inner)
        }

        private fun buildActions(row: Row) {
            val slot = row.slot
            val actions = mutableListOf<CardAction>()
            val bound = slot.boundStatus
            if (bound == null || slot.boundConnectionId == null) {
                actions +=
                    if (row.connections.isEmpty()) {
                        CardAction(R.drawable.ic_satellite, ctx.getString(R.string.tz_action_find_hosts), outlined = false, kind = ActionKind.FIND_HOSTS)
                    } else {
                        CardAction(R.drawable.ic_tune, ctx.getString(R.string.tz_action_configure), outlined = true, kind = ActionKind.CONFIGURE)
                    }
            } else {
                val connected = bound.live == LinkState.Connected
                if (slot.inputType == SlotInputType.VIRTUAL && connected) {
                    actions += CardAction(R.drawable.ic_open_gamepad, ctx.getString(R.string.action_open_gamepad), outlined = false, kind = ActionKind.GAMEPAD)
                }
                val touchpadMode = row.touchpadModes[slot.boundConnectionId] ?: TouchpadModeValue.OFF
                if (bound.kind == ConnectionKind.SATELLITE && connected && touchpadMode != TouchpadModeValue.OFF) {
                    actions += CardAction(R.drawable.ic_open_touchpad, ctx.getString(R.string.action_open_touchpad), outlined = false, kind = ActionKind.TOUCHPAD)
                }
                actions += CardAction(R.drawable.ic_tune, ctx.getString(R.string.tz_action_configure), outlined = true, kind = ActionKind.CONFIGURE)
            }

            val inner = LinearLayout(ctx)
            inner.orientation = LinearLayout.VERTICAL
            inner.setPaddingRelative(dp(14), dp(12), dp(14), dp(14))
            actions.forEachIndexed { i, action ->
                val btn = actionButton(inner, action) { dispatch(action.kind, slot.id) }
                val lp = btn.layoutParams as LinearLayout.LayoutParams
                if (i > 0) lp.topMargin = dp(8)
                btn.layoutParams = lp
                inner.addView(btn)
            }
            b.llActions.addView(divider())
            b.llActions.addView(inner)
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

        private fun connectionPills(
            row: Row,
            kind: ConnectionKind?,
        ): List<View> {
            val card = row.pathCard
            val isUsb = card?.transport == Transport.Usb
            val isBt = row.slot.inputType == SlotInputType.PHYSICAL && card?.transport == Transport.Bluetooth
            return when {
                row.slot.inputType == SlotInputType.VIRTUAL ->
                    listOf(factPill(ctx.getString(R.string.tz_link_onscreen), R.drawable.ic_gamepad_virtual))
                isUsb -> {
                    val out = mutableListOf<View>(factPill(ctx.getString(R.string.tz_link_usb), R.drawable.ic_usb))
                    if (kind != null && card != null) {
                        out +=
                            if (card.currentMode == InputPathMode.Direct) {
                                if (card.risk == PathRisk.GuessedLayout) {
                                    warnPill(ctx.getString(R.string.tz_mode_direct), R.drawable.ic_bolt)
                                } else {
                                    onPill(ctx.getString(R.string.tz_mode_direct), R.drawable.ic_bolt)
                                }
                            } else {
                                capPill(ctx.getString(R.string.tz_mode_standard), R.drawable.ic_cable)
                            }
                    }
                    out
                }
                isBt -> listOf(factPill(ctx.getString(R.string.tz_link_bluetooth), R.drawable.ic_bluetooth))
                else -> listOf(factPill(ctx.getString(R.string.tz_link_usb), R.drawable.ic_usb))
            }
        }

        private fun typePill(
            row: Row,
            bound: ConnectionSummary,
        ): View? {
            val label =
                when (bound.kind) {
                    ConnectionKind.SATELLITE -> {
                        val type = bound.satelliteControllerTypes[row.slot.id] ?: com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
                        if (type == CONTROLLER_TYPE_PLAYSTATION) {
                            ctx.getString(R.string.picker_type_playstation)
                        } else {
                            ctx.getString(R.string.picker_type_xbox)
                        }
                    }
                    ConnectionKind.BLUETOOTH -> bound.btProfile
                }
            return label?.let { factPill(it, null) }
        }

        // Reports the configured (not live-gated) routing: motion only carries on a Satellite host
        // emulating PlayStation; touchpad only on a Satellite host.
        private fun functionPills(
            row: Row,
            bound: ConnectionSummary,
        ): List<View> {
            val out = mutableListOf<View>()
            val card = row.pathCard
            val rumblePresent =
                card != null &&
                    (if (card.currentMode == InputPathMode.Direct) card.direct.rumble else card.standard.rumble)
            if (rumblePresent) {
                out += onPill(funcValue(R.string.tz_func_rumble, R.string.tz_state_on), R.drawable.ic_rumble)
            }

            val type = bound.satelliteControllerTypes[row.slot.id] ?: com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
            val motionAvailable =
                row.motionCap.hasGyro &&
                    bound.kind == ConnectionKind.SATELLITE &&
                    type == CONTROLLER_TYPE_PLAYSTATION
            if (motionAvailable) {
                val on = row.motionCap.userEnabled
                out +=
                    if (on) {
                        onPill(funcValue(R.string.tz_func_motion, R.string.tz_state_on), R.drawable.ic_motion)
                    } else {
                        offPill(funcValue(R.string.tz_func_motion, R.string.tz_state_off), R.drawable.ic_motion)
                    }
            }

            if (bound.kind == ConnectionKind.SATELLITE) {
                val mode = row.touchpadModes[row.slot.boundConnectionId] ?: TouchpadModeValue.OFF
                val valueRes =
                    when (mode) {
                        TouchpadModeValue.DS4 -> R.string.touchpad_mode_pad
                        TouchpadModeValue.MOUSE -> R.string.touchpad_mode_mouse
                        else -> R.string.touchpad_mode_off
                    }
                val label = ctx.getString(R.string.tz_func_value, ctx.getString(R.string.tz_func_touchpad), ctx.getString(valueRes))
                val icon = if (mode == TouchpadModeValue.MOUSE) R.drawable.ic_mouse else R.drawable.ic_touchpad
                out += if (mode == TouchpadModeValue.OFF) offPill(label, icon) else onPill(label, icon)
            }
            return out
        }

        private fun funcValue(
            nameRes: Int,
            valueRes: Int,
        ): String = ctx.getString(R.string.tz_func_value, ctx.getString(nameRes), ctx.getString(valueRes))

        private fun decisionsContainer(): LinearLayout {
            val inner = LinearLayout(ctx)
            inner.orientation = LinearLayout.VERTICAL
            inner.setPaddingRelative(dp(14), dp(4), dp(14), dp(12))
            return inner
        }

        private fun decisionRow(
            label: String,
            values: List<View>,
        ): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPaddingRelative(0, dp(5), 0, dp(5))
            row.addView(microLabel(label), LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            val container = LinearLayout(ctx)
            container.orientation = LinearLayout.HORIZONTAL
            container.gravity = Gravity.CENTER_VERTICAL
            values.forEachIndexed { i, v ->
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.marginStart = dp(6)
                container.addView(v, lp)
            }
            row.addView(container, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        private fun functionRow(pills: List<View>): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPaddingRelative(0, dp(8), 0, dp(2))
            if (pills.isEmpty()) {
                val none = TextView(ctx)
                none.text = ctx.getString(R.string.tz_card_no_functions)
                none.setTextColor(color(R.color.colorMuted))
                none.textSize = 12f
                row.addView(none)
            } else {
                pills.forEachIndexed { i, v ->
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    if (i > 0) lp.marginStart = dp(6)
                    row.addView(v, lp)
                }
            }
            return row
        }

        private fun microLabel(text: String): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextColor(color(R.color.colorMuted))
            tv.textSize = 10f
            tv.typeface = Typeface.MONOSPACE
            tv.isAllCaps = true
            tv.letterSpacing = 0.08f
            tv.includeFontPadding = false
            return tv
        }

        private fun monoValue(text: String): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextColor(color(R.color.colorOnSurface))
            tv.textSize = 12.5f
            tv.typeface = Typeface.MONOSPACE
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.maxLines = 1
            return tv
        }

        private fun notBoundValue(): View {
            val container = LinearLayout(ctx)
            container.orientation = LinearLayout.HORIZONTAL
            container.gravity = Gravity.CENTER_VERTICAL
            val dot = View(ctx)
            val dotBg = GradientDrawable()
            dotBg.shape = GradientDrawable.OVAL
            dotBg.setColor(color(R.color.colorWarning))
            dot.background = dotBg
            container.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
            val tv = TextView(ctx)
            tv.text = ctx.getString(R.string.tz_card_not_bound)
            tv.setTextColor(color(R.color.colorWarning))
            tv.textSize = 13f
            tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(6)
            container.addView(tv, lp)
            return container
        }

        private fun divider(): View {
            val v = View(ctx)
            v.setBackgroundColor(color(R.color.colorOutlineVariant))
            v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            return v
        }

        private fun actionButton(
            parent: ViewGroup,
            action: CardAction,
            onClick: () -> Unit,
        ): MaterialButton {
            val btn = LayoutInflater.from(ctx).inflate(R.layout.tz_action_button, parent, false) as MaterialButton
            btn.text = action.label
            btn.setIconResource(action.icon)
            if (action.outlined) {
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(color(R.color.colorOnSurface))
            }
            btn.setOnClickListener { onClick() }
            return btn
        }

        private fun factPill(
            text: String,
            @DrawableRes icon: Int?,
        ): TextView =
            pill(text, icon, color(R.color.colorOnSurface), color(R.color.colorSurfaceVariant), color(R.color.colorOutlineVariant))

        private fun onPill(
            text: String,
            @DrawableRes icon: Int?,
        ): TextView =
            pill(text, icon, color(R.color.colorPrimary), color(R.color.colorIconContainerFill), color(R.color.colorOutline))

        private fun warnPill(
            text: String,
            @DrawableRes icon: Int?,
        ): TextView =
            pill(text, icon, color(R.color.colorTertiary), color(R.color.colorTertiaryContainer), Color.TRANSPARENT)

        private fun capPill(
            text: String,
            @DrawableRes icon: Int?,
        ): TextView =
            pill(text, icon, color(R.color.colorOnSurfaceVariant), Color.TRANSPARENT, color(R.color.colorOutlineVariant))

        private fun offPill(
            text: String,
            @DrawableRes icon: Int?,
        ): TextView =
            pill(text, icon, color(R.color.colorMuted), Color.TRANSPARENT, color(R.color.colorOutlineVariant), dashed = true, alpha = 0.6f)

        private fun pill(
            text: String,
            @DrawableRes icon: Int?,
            @ColorInt fg: Int,
            @ColorInt bg: Int,
            @ColorInt stroke: Int,
            dashed: Boolean = false,
            alpha: Float = 1f,
        ): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextColor(fg)
            tv.textSize = 11.5f
            tv.includeFontPadding = false
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.alpha = alpha
            val startPad = if (icon != null) dp(7) else dp(9)
            tv.setPaddingRelative(startPad, dp(4), dp(9), dp(4))
            val gd = GradientDrawable()
            gd.cornerRadius = dp(4).toFloat()
            gd.setColor(bg)
            if (dashed) {
                gd.setStroke(dp(1), stroke, dp(3).toFloat(), dp(2).toFloat())
            } else {
                gd.setStroke(dp(1), stroke)
            }
            tv.background = gd
            if (icon != null) {
                val d = AppCompatResources.getDrawable(ctx, icon)
                val s = dp(14)
                d?.setBounds(0, 0, s, s)
                tv.setCompoundDrawablesRelative(d, null, null, null)
                tv.compoundDrawablePadding = dp(4)
                TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(fg))
            }
            return tv
        }

        @ColorInt
        private fun color(res: Int): Int = ctx.getColor(res)

        private fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
    }

    private data class CardAction(
        @DrawableRes val icon: Int,
        val label: String,
        val outlined: Boolean,
        val kind: ActionKind,
    )

    private enum class ActionKind { GAMEPAD, TOUCHPAD, CONFIGURE, FIND_HOSTS }

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
