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
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.databinding.ItemControllerBinding

interface SlotActionListener {
    fun onSlotTapped(slotId: String)
    fun onDestWifi(slotId: String)
    fun onDestBt(slotId: String)
    fun onScan(slotId: String)
    fun onServerSelected(slotId: String, server: DiscoveredServer)
    fun onBtStart(slotId: String)
    fun onDisconnect(slotId: String)
    fun onOpenGamepad()
}

class ControllerAdapter(
    private val listener: SlotActionListener,
) : ListAdapter<ControllerSlot, ControllerAdapter.VH>(Diff) {

    private var servers: List<DiscoveredServer> = emptyList()
    private var scanning = false

    fun submitSlots(slots: List<ControllerSlot>, discoveredServers: List<DiscoveredServer>, isScanning: Boolean) {
        servers = discoveredServers
        scanning = isScanning
        submitList(slots.toList())
    }

    inner class VH(private val b: ItemControllerBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(slot: ControllerSlot) {
            val ctx = b.root.context
            val dp = ctx.resources.displayMetrics.density

            // ── Header ──
            val isVirtual = slot.inputType == SlotInputType.VIRTUAL
            b.ivControllerType.setImageResource(
                if (isVirtual) android.R.drawable.ic_menu_compass else R.drawable.ctrl_xbox
            )
            b.tvControllerName.text = slot.name
            b.tvSlotStatus.text = slotStatusText(slot)

            // Status dot
            initDot(b.dotStatus)
            setDot(b.dotStatus, when {
                slot.isDisconnecting -> R.color.colorWarning
                slot.isConnected -> R.color.colorSuccess
                slot.connectionState == SlotConnectionState.CONNECTING -> R.color.colorPrimary
                else -> R.color.colorMuted
            })

            // Alpha for disconnecting
            b.root.alpha = if (slot.isDisconnecting) 0.5f else 1f

            // Card tap → expand/collapse
            b.cardRoot.strokeColor = ctx.getColor(
                if (slot.destExpanded) R.color.colorPrimary else R.color.colorCardStroke
            )
            b.root.setOnClickListener { listener.onSlotTapped(slot.id) }

            // ── Body ──
            b.llBody.visibility = if (slot.destExpanded) View.VISIBLE else View.GONE
            if (!slot.destExpanded) return

            // Destination selector buttons
            highlightBtn(b.btnDestWifi, slot.destType == SlotDestType.WIFI)
            highlightBtn(b.btnDestBt, slot.destType == SlotDestType.BLUETOOTH)
            b.btnDestWifi.setOnClickListener { listener.onDestWifi(slot.id) }
            b.btnDestBt.setOnClickListener { listener.onDestBt(slot.id) }

            // ── Dynamic content ──
            b.llDestContent.removeAllViews()
            b.llActions.visibility = View.GONE
            b.btnDisconnect.visibility = View.GONE
            b.btnOpenGamepad.visibility = View.GONE

            when (slot.destType) {
                SlotDestType.WIFI -> buildWifiContent(slot, dp)
                SlotDestType.BLUETOOTH -> buildBtContent(slot)
                SlotDestType.NONE -> addLabel(b.llDestContent, dp, "Choose a destination above", R.color.colorMuted)
            }

            // Virtual-only: open gamepad button
            if (isVirtual && slot.isConnected) {
                b.btnOpenGamepad.visibility = View.VISIBLE
                b.btnOpenGamepad.setOnClickListener { listener.onOpenGamepad() }
            }
        }

        private fun buildWifiContent(slot: ControllerSlot, dp: Float) {
            b.llActions.visibility = View.VISIBLE
            if (slot.isConnected) {
                b.btnAction.text = "RESCAN"
                b.btnAction.setOnClickListener { listener.onScan(slot.id) }
                b.btnDisconnect.visibility = View.VISIBLE
                b.btnDisconnect.setOnClickListener { listener.onDisconnect(slot.id) }
                addLabel(b.llDestContent, dp, "Connected to ${slot.connectedName}", R.color.colorSuccess)
            } else if (scanning) {
                b.btnAction.text = "SCANNING…"
                b.btnAction.isEnabled = false
                addLabel(b.llDestContent, dp, "Searching for servers…", R.color.colorMuted)
            } else {
                b.btnAction.text = if (servers.isNotEmpty()) "RESCAN" else "SCAN"
                b.btnAction.isEnabled = true
                b.btnAction.setOnClickListener { listener.onScan(slot.id) }
                if (servers.isEmpty()) {
                    addLabel(b.llDestContent, dp, "No servers found yet", R.color.colorMuted)
                } else {
                    addLabel(b.llDestContent, dp, "Select a server:", R.color.colorMuted)
                    for (s in servers) addServerItem(b.llDestContent, dp, slot.id, s)
                }
            }
        }

        private fun buildBtContent(slot: ControllerSlot) {
            val dp = b.root.context.resources.displayMetrics.density
            b.llActions.visibility = View.VISIBLE
            if (slot.isConnected) {
                b.btnAction.text = "STOP"
                b.btnAction.setOnClickListener { listener.onDisconnect(slot.id) }
                b.btnDisconnect.visibility = View.GONE
                addLabel(b.llDestContent, dp, "Connected to ${slot.connectedName}", R.color.colorSuccess)
            } else if (slot.btRegistered) {
                b.btnAction.text = "STOP"
                b.btnAction.setOnClickListener { listener.onDisconnect(slot.id) }
                addLabel(b.llDestContent, dp, "Waiting for host…", R.color.colorMuted)
            } else {
                b.btnAction.text = "START"
                b.btnAction.setOnClickListener { listener.onBtStart(slot.id) }
                addLabel(b.llDestContent, dp, "Register as Bluetooth gamepad", R.color.colorMuted)
            }
        }

        // ── Helpers ──

        private fun slotStatusText(s: ControllerSlot) = when {
            s.isDisconnecting -> "Disconnecting… ${s.disconnectTimeLeft}s"
            s.isConnected -> s.connectedName ?: "Connected"
            s.connectionState == SlotConnectionState.CONNECTING -> "Connecting…"
            s.destType != SlotDestType.NONE -> "Ready"
            else -> "Tap to configure"
        }

        private fun highlightBtn(btn: com.google.android.material.button.MaterialButton, active: Boolean) {
            val ctx = btn.context
            if (active) {
                btn.setBackgroundColor(ctx.getColor(R.color.colorPrimaryDark))
                btn.setTextColor(ctx.getColor(R.color.colorOnSurface))
            } else {
                btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(ctx.getColor(R.color.colorOnSurface))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorOutline))
            }
        }

        private fun initDot(v: View) {
            if (v.background is GradientDrawable) return
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(v.context.getColor(R.color.colorMuted)) }
        }

        private fun setDot(v: View, colorRes: Int) {
            (v.background as? GradientDrawable)?.setColor(v.context.getColor(colorRes))
        }
    }

    // ── Shared dynamic-view builders (called from VH) ──

    private fun addLabel(parent: LinearLayout, dp: Float, text: String, colorRes: Int) {
        val ctx = parent.context
        parent.addView(TextView(ctx).apply {
            this.text = text; setTextColor(ctx.getColor(colorRes)); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * dp).toInt()
            }
        })
    }

    private fun addServerItem(parent: LinearLayout, dp: Float, slotId: String, server: DiscoveredServer) {
        val ctx = parent.context
        val item = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (10 * dp).toInt(); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(ctx.getColor(R.color.colorBackground)); cornerRadius = 6 * dp
                setStroke((1 * dp).toInt(), ctx.getColor(R.color.colorOutline))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).toInt()
            }
            setOnClickListener { listener.onServerSelected(slotId, server) }
            isClickable = true; isFocusable = true
        }
        item.addView(TextView(ctx).apply { text = server.name; setTextColor(ctx.getColor(R.color.colorOnSurface)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
        item.addView(TextView(ctx).apply { text = "${server.ip} • UDP:${server.udpPort}"; setTextColor(ctx.getColor(R.color.colorMuted)); textSize = 11f; typeface = Typeface.MONOSPACE })
        parent.addView(item)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemControllerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object Diff : DiffUtil.ItemCallback<ControllerSlot>() {
        override fun areItemsTheSame(o: ControllerSlot, n: ControllerSlot) = o.id == n.id
        override fun areContentsTheSame(o: ControllerSlot, n: ControllerSlot) = o == n
    }
}
