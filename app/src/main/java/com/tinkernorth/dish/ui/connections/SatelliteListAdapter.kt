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
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.statusChipText
import com.tinkernorth.dish.ui.main.compatPillParts

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
            bindPrimaryAction(row)
            bindForgetAction(row)
            paintCompat(row.compat)
        }

        private fun bindPrimaryAction(row: SatelliteRow.Known) {
            val c = row.summary
            when (primaryActionFor(c.live)) {
                RowAction.DISCONNECT -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_disconnect))
                    b.btnRowAction.setOnClickListener { listener.onDisconnect(c.id) }
                }
                RowAction.CONNECTING -> {
                    b.btnRowAction.setLoading(
                        true,
                        ctx.getString(R.string.chip_status_connecting),
                        ctx.getString(R.string.action_connect),
                    )
                    b.btnRowAction.setOnClickListener(null)
                }
                RowAction.REPAIR -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_repair_short))
                    b.btnRowAction.setOnClickListener { listener.onRepair(c.id) }
                }
                RowAction.CONNECT -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_connect))
                    b.btnRowAction.setOnClickListener { listener.onConnect(row) }
                }
            }
        }

        private fun bindForgetAction(row: SatelliteRow.Known) {
            b.btnRowSecondary.visibility = View.VISIBLE
            b.btnRowSecondary.text = ctx.getString(R.string.action_forget_short)
            b.btnRowSecondary.setOnClickListener { listener.onForget(row.summary.id) }
        }

        private fun paintCompat(compat: DishProtocolCompat) {
            val parts = compatPillParts(compat)
            if (parts == null) {
                b.tvRowUpdate.visibility = View.GONE
                return
            }
            val (text, tone) = parts
            b.tvRowUpdate.visibility = View.VISIBLE
            b.tvRowUpdate.setText(text)
            b.tvRowUpdate.setBackgroundResource(tone.background)
            b.tvRowUpdate.setTextColor(ctx.getColor(tone.foreground))
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
            b.tvRowUpdate.visibility = View.GONE
        }
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_EMPTY = 1

        private val Diff = SatelliteRowDiff()
    }
}

private class SatelliteRowDiff : DiffUtil.ItemCallback<SatelliteRow>() {
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
