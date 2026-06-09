// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.statusChipText

sealed interface SatelliteRow {
    data class Known(
        val summary: ConnectionSummary,
    ) : SatelliteRow

    data class Discovered(
        val server: DiscoveredServer,
    ) : SatelliteRow

    data class Empty(
        val message: String,
    ) : SatelliteRow
}

interface SatelliteRowListener {
    fun onConnect(row: SatelliteRow)

    fun onDisconnect(id: String)

    fun onRepair(id: String)

    fun onForget(id: String)
}

class SatelliteListAdapter(
    private val listener: SatelliteRowListener,
) : ListAdapter<SatelliteRow, RecyclerView.ViewHolder>(Diff) {
    override fun getItemViewType(position: Int): Int = if (getItem(position) is SatelliteRow.Empty) TYPE_EMPTY else TYPE_ROW

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_EMPTY) {
            EmptyVH(inflater.inflate(R.layout.item_connection_empty, parent, false))
        } else {
            RowVH(RowConnectionBinding.inflate(inflater, parent, false), listener)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = getItem(position)) {
            is SatelliteRow.Empty -> (holder as EmptyVH).bind(row.message)
            else -> (holder as RowVH).bind(row)
        }
    }

    class EmptyVH(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(message: String) {
            (itemView as TextView).text = message
        }
    }

    class RowVH(
        private val b: RowConnectionBinding,
        private val listener: SatelliteRowListener,
    ) : RecyclerView.ViewHolder(b.root) {
        private val ctx get() = b.root.context

        fun bind(row: SatelliteRow) {
            when (row) {
                is SatelliteRow.Known -> bindKnown(row)
                is SatelliteRow.Discovered -> bindDiscovered(row)
                is SatelliteRow.Empty -> Unit
            }
        }

        private fun bindKnown(row: SatelliteRow.Known) {
            val c = row.summary
            b.paintConnection(c.label, c.detail, statusChipText(ctx, c.live), ConnectionKind.SATELLITE, c.live)
            when (c.live) {
                LinkState.Connected, LinkState.Unstable -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_disconnect))
                    b.btnRowAction.setOnClickListener { listener.onDisconnect(c.id) }
                }
                LinkState.Connecting -> {
                    b.btnRowAction.setLoading(
                        true,
                        ctx.getString(R.string.chip_status_connecting),
                        ctx.getString(R.string.action_connect),
                    )
                    b.btnRowAction.setOnClickListener(null)
                }
                LinkState.Stale -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_repair_short))
                    b.btnRowAction.setOnClickListener { listener.onRepair(c.id) }
                }
                LinkState.Saved, LinkState.Ready, LinkState.Found -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_connect))
                    b.btnRowAction.setOnClickListener { listener.onConnect(row) }
                }
            }
            b.btnRowSecondary.visibility = View.VISIBLE
            b.btnRowSecondary.text = ctx.getString(R.string.action_forget_short)
            b.btnRowSecondary.setOnClickListener { listener.onForget(c.id) }
        }

        private fun bindDiscovered(row: SatelliteRow.Discovered) {
            val s = row.server
            b.paintConnection(
                s.name.ifEmpty { s.ip },
                ctx.getString(R.string.discovered_row_detail, s.ip, s.udpPort),
                ctx.getString(R.string.discovered_row_status, ctx.getString(s.source.labelRes)),
                ConnectionKind.SATELLITE,
                LinkState.Found,
            )
            b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_connect))
            b.btnRowAction.setOnClickListener { listener.onConnect(row) }
            b.btnRowSecondary.visibility = View.GONE
            b.btnRowSecondary.setOnClickListener(null)
        }
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_EMPTY = 1

        private val Diff =
            object : DiffUtil.ItemCallback<SatelliteRow>() {
                override fun areItemsTheSame(
                    o: SatelliteRow,
                    n: SatelliteRow,
                ): Boolean =
                    when {
                        o is SatelliteRow.Known && n is SatelliteRow.Known -> o.summary.id == n.summary.id
                        o is SatelliteRow.Discovered && n is SatelliteRow.Discovered ->
                            SatelliteConnection.idFor(o.server) == SatelliteConnection.idFor(n.server)
                        o is SatelliteRow.Empty && n is SatelliteRow.Empty -> true
                        else -> false
                    }

                override fun areContentsTheSame(
                    o: SatelliteRow,
                    n: SatelliteRow,
                ): Boolean = o == n
            }
    }
}
