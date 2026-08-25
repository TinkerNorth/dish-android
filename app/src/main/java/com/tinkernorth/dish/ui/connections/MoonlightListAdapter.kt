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
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.statusChipText

/** Rows for the Moonlight-hosts section, the sibling of [SatelliteRow]. */
sealed interface MoonlightRow {
    data class Known(
        val summary: ConnectionSummary,
    ) : MoonlightRow

    data class Discovered(
        val host: MoonlightHost,
    ) : MoonlightRow

    data class Empty(
        val message: String,
    ) : MoonlightRow
}

interface MoonlightRowListener {
    fun onConnectKnown(summary: ConnectionSummary)

    fun onConnectDiscovered(host: MoonlightHost)

    fun onDisconnect(id: String)

    fun onForget(id: String)
}

class MoonlightListAdapter(
    private val listener: MoonlightRowListener,
) : ListAdapter<MoonlightRow, RecyclerView.ViewHolder>(Diff) {
    override fun getItemViewType(position: Int): Int = if (getItem(position) is MoonlightRow.Empty) TYPE_EMPTY else TYPE_ROW

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
            is MoonlightRow.Empty -> (holder as EmptyVH).bind(row.message)
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
        private val listener: MoonlightRowListener,
    ) : RecyclerView.ViewHolder(b.root) {
        private val ctx get() = b.root.context

        fun bind(row: MoonlightRow) {
            when (row) {
                is MoonlightRow.Known -> bindKnown(row)
                is MoonlightRow.Discovered -> bindDiscovered(row)
                is MoonlightRow.Empty -> Unit
            }
        }

        private fun bindKnown(row: MoonlightRow.Known) {
            val c = row.summary
            b.paintConnection(c.label, c.detail, statusChipText(ctx, c.live), ConnectionKind.MOONLIGHT, c.live)
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
                LinkState.Saved, LinkState.Ready, LinkState.Found, LinkState.Stale -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_connect))
                    b.btnRowAction.setOnClickListener { listener.onConnectKnown(c) }
                }
            }
            b.btnRowSecondary.visibility = View.VISIBLE
            b.btnRowSecondary.text = ctx.getString(R.string.action_forget_short)
            b.btnRowSecondary.setOnClickListener { listener.onForget(c.id) }
        }

        private fun bindDiscovered(row: MoonlightRow.Discovered) {
            val h = row.host
            b.paintConnection(
                h.name.ifEmpty { h.address },
                ctx.getString(R.string.moonlight_row_detail, h.address),
                ctx.getString(R.string.discovered_row_status, ctx.getString(R.string.discovery_source_mdns)),
                ConnectionKind.MOONLIGHT,
                LinkState.Found,
            )
            b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_connect))
            b.btnRowAction.setOnClickListener { listener.onConnectDiscovered(h) }
            b.btnRowSecondary.visibility = View.GONE
            b.btnRowSecondary.setOnClickListener(null)
        }
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_EMPTY = 1

        private val Diff =
            object : DiffUtil.ItemCallback<MoonlightRow>() {
                override fun areItemsTheSame(
                    o: MoonlightRow,
                    n: MoonlightRow,
                ): Boolean =
                    when {
                        o is MoonlightRow.Known && n is MoonlightRow.Known -> o.summary.id == n.summary.id
                        o is MoonlightRow.Discovered && n is MoonlightRow.Discovered -> o.host.id == n.host.id
                        o is MoonlightRow.Empty && n is MoonlightRow.Empty -> true
                        else -> false
                    }

                override fun areContentsTheSame(
                    o: MoonlightRow,
                    n: MoonlightRow,
                ): Boolean = o == n
            }
    }
}

// Known hosts first (from the composer summaries), then discovered hosts not already known.
fun moonlightRows(
    conns: List<ConnectionSummary>,
    discovered: List<MoonlightHost>,
): List<MoonlightRow> {
    val known = conns.filter { it.kind == ConnectionKind.MOONLIGHT }
    val knownIds = known.mapTo(mutableSetOf()) { it.id }
    return buildList {
        known.forEach { add(MoonlightRow.Known(it)) }
        discovered.forEach { host ->
            if (host.id !in knownIds) add(MoonlightRow.Discovered(host))
        }
    }
}
