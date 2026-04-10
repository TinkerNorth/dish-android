package com.tinkernorth.dish.ui.main

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.model.ControllerEntry
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.databinding.ItemControllerBinding

class ControllerAdapter(
    private val onRescanClicked: () -> Unit = {},
    private val onServerSelected: (DiscoveredServer) -> Unit = {},
) : ListAdapter<ControllerEntry, ControllerAdapter.ViewHolder>(DiffCallback) {

    private var isConnected = false
    private var discoveredServers: List<DiscoveredServer> = emptyList()
    private var isScanning = false

    fun submitList(
        list: List<ControllerEntry>,
        connected: Boolean,
        servers: List<DiscoveredServer>,
        scanning: Boolean
    ) {
        val stateChanged = isConnected != connected
                || discoveredServers != servers
                || isScanning != scanning
        isConnected = connected
        discoveredServers = servers
        isScanning = scanning
        // Submit a copy so DiffUtil always sees a new list object, and if
        // only the adapter-level state changed force a full rebind.
        submitList(list.toList()) {
            if (stateChanged) notifyDataSetChanged()
        }
    }

    inner class ViewHolder(
        private val binding: ItemControllerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ControllerEntry) {
            val ctx = binding.root.context
            val dp = ctx.resources.displayMetrics.density

            binding.tvControllerName.text = item.name
            binding.tvControllerId.text = "#${item.controllerIndex}"

            // Status dot next to controller type icon
            val dotColor = when {
                item.isDisconnected -> R.color.colorWarning
                isConnected -> R.color.colorSuccess
                else -> R.color.colorMuted
            }
            binding.ivControllerType.setColorFilter(ctx.getColor(dotColor))

            if (item.isDisconnected) {
                binding.tvDisconnectCountdown.visibility = View.VISIBLE
                binding.tvDisconnectCountdown.text = "${item.disconnectTimeLeft}s"
                binding.root.alpha = 0.5f
            } else {
                binding.tvDisconnectCountdown.visibility = View.GONE
                binding.root.alpha = 1.0f
            }

            // Dynamic content area below the header
            val contentContainer = (binding.root as ViewGroup).getChildAt(0) as LinearLayout
            // Remove any previously added dynamic views (keep first 2: header row + btnRescan)
            while (contentContainer.childCount > 2) {
                contentContainer.removeViewAt(2)
            }

            when {
                item.isDisconnected -> {
                    binding.btnRescan.visibility = View.GONE
                    addLabel(contentContainer, dp,
                        "Controller disconnected — removing in ${item.disconnectTimeLeft}s",
                        R.color.colorWarning)
                }
                isConnected -> {
                    binding.btnRescan.visibility = View.GONE
                    addLabel(contentContainer, dp, "Streaming", R.color.colorSuccess)
                }
                isScanning -> {
                    binding.btnRescan.visibility = View.GONE
                    addLabel(contentContainer, dp, "Scanning for servers…", R.color.colorMuted)
                }
                discoveredServers.isNotEmpty() -> {
                    binding.btnRescan.visibility = View.VISIBLE
                    binding.btnRescan.text = "RESCAN"
                    binding.btnRescan.setOnClickListener { onRescanClicked() }
                    addLabel(contentContainer, dp, "Select a server:", R.color.colorMuted)
                    for (server in discoveredServers) {
                        addServerItem(contentContainer, dp, server)
                    }
                }
                else -> {
                    // No servers found — show scan button
                    binding.btnRescan.visibility = View.VISIBLE
                    binding.btnRescan.text = "SCAN FOR SERVERS"
                    binding.btnRescan.setOnClickListener { onRescanClicked() }
                    addLabel(contentContainer, dp, "No servers found", R.color.colorMuted)
                }
            }
        }

        private fun addLabel(parent: LinearLayout, dp: Float, text: String, colorRes: Int) {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                this.text = text
                setTextColor(ctx.getColor(colorRes))
                textSize = 13f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (4 * dp).toInt()
                lp.marginStart = (16 * dp).toInt()
                lp.marginEnd = (16 * dp).toInt()
                layoutParams = lp
            }
            parent.addView(tv)
        }

        private fun addServerItem(parent: LinearLayout, dp: Float, server: DiscoveredServer) {
            val ctx = parent.context
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(ctx.getColor(R.color.colorBackground))
                    cornerRadius = 4 * dp
                    setStroke((1 * dp).toInt(), ctx.getColor(R.color.colorOutline))
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (8 * dp).toInt()
                lp.marginStart = (16 * dp).toInt()
                lp.marginEnd = (16 * dp).toInt()
                layoutParams = lp
                setOnClickListener { onServerSelected(server) }
                isClickable = true
                isFocusable = true
            }
            val tvName = TextView(ctx).apply {
                text = server.name
                setTextColor(ctx.getColor(R.color.colorOnSurface))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvMeta = TextView(ctx).apply {
                text = "${server.ip}  •  UDP:${server.udpPort}"
                setTextColor(ctx.getColor(R.color.colorMuted))
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
            item.addView(tvName)
            item.addView(tvMeta)
            parent.addView(item)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemControllerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ControllerEntry>() {
        override fun areItemsTheSame(oldItem: ControllerEntry, newItem: ControllerEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ControllerEntry, newItem: ControllerEntry): Boolean {
            return oldItem == newItem
        }
    }
}
