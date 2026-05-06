// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.databinding.ItemControllerBinding

interface SlotActionListener {
    fun onSlotTapped(slotId: String)

    fun onBind(
        slotId: String,
        connectionId: String,
    )

    fun onUnbind(slotId: String)

    fun onOpenGamepad()
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
        fun bind(row: Row) {
            val slot = row.slot
            val ctx = b.root.context
            val dp = ctx.resources.displayMetrics.density
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL

            b.ivControllerType.setImageResource(
                if (isVirtual) android.R.drawable.ic_menu_compass else R.drawable.ctrl_xbox,
            )
            b.tvControllerName.text = slot.name
            b.tvSlotStatus.text = slotStatusText(slot)

            initDot(b.dotStatus)
            setDot(
                b.dotStatus,
                when {
                    slot.isDisconnecting -> R.color.colorWarning
                    slot.boundStatus?.live == ConnectionLive.CONNECTED -> R.color.colorSuccess
                    slot.boundStatus?.live == ConnectionLive.CONNECTING -> R.color.colorPrimary
                    else -> R.color.colorMuted
                },
            )
            b.root.alpha = if (slot.isDisconnecting) 0.5f else 1f

            b.cardRoot.strokeColor =
                ctx.getColor(
                    if (row.expanded) R.color.colorPrimary else R.color.colorCardStroke,
                )
            b.root.setOnClickListener { listener.onSlotTapped(slot.id) }

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
            if (isVirtual && slot.boundStatus?.live == ConnectionLive.CONNECTED) {
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
            val ownedByOther = c.boundSlotId != null && c.boundSlotId != slot.id
            val row =
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
                                ctx.getColor(
                                    if (bound) R.color.colorPrimary else R.color.colorOutline,
                                ),
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
                    if (!ownedByOther) setOnClickListener { listener.onBind(slot.id, c.id) }
                }
            val prefix =
                when (c.kind) {
                    ConnectionKind.WIFI -> "📡 "
                    ConnectionKind.BLUETOOTH -> "🔗 "
                }
            val statusSuffix =
                when (c.live) {
                    ConnectionLive.CONNECTED -> " • connected"
                    ConnectionLive.CONNECTING -> " • connecting…"
                    ConnectionLive.IDLE -> ""
                }
            row.addView(
                TextView(ctx).apply {
                    text = "$prefix${c.label}$statusSuffix"
                    setTextColor(ctx.getColor(R.color.colorOnSurface))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                },
            )
            val detail =
                buildString {
                    append(c.detail)
                    if (bound) {
                        append(" • bound here")
                    } else if (ownedByOther) {
                        append(" • in use")
                    }
                }
            row.addView(
                TextView(ctx).apply {
                    text = detail
                    setTextColor(ctx.getColor(R.color.colorMuted))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                },
            )
            parent.addView(row)
        }

        private fun slotStatusText(s: ControllerSlot) =
            when {
                s.isDisconnecting -> "Disconnecting… ${s.disconnectTimeLeft}s"
                s.boundStatus?.live == ConnectionLive.CONNECTED ->
                    "→ ${s.boundStatus.label}"
                s.boundStatus?.live == ConnectionLive.CONNECTING -> "Connecting…"
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
