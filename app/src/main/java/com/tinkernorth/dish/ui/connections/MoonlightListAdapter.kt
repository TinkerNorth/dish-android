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
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.main.chipTextRes

/** Rows for the Moonlight-hosts section, the sibling of [SatelliteRow]. */
sealed interface MoonlightRow {
    data class Known(
        val summary: ConnectionSummary,
        val trust: MoonlightTrustState,
        val controllerCount: Int,
    ) : MoonlightRow

    data class Discovered(
        val host: MoonlightHost,
    ) : MoonlightRow

    data class Empty(
        val message: String,
    ) : MoonlightRow
}

interface MoonlightRowListener {
    fun onPairKnown(summary: ConnectionSummary)

    fun onPairDiscovered(host: MoonlightHost)

    fun onQuitSession(id: String)

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

        // Pairing is remembered trust, not a live link, so the chip says which of the
        // three trust words applies and never lights up as though the host were online.
        // The session lives in the binding; all this screen offers is the way out of one.
        private fun bindKnown(row: MoonlightRow.Known) {
            val c = row.summary
            b.paintConnection(c.label, detailFor(row), ctx.getString(row.trust.chipTextRes()), ConnectionKind.MOONLIGHT, c.live)
            if (row.controllerCount > 0) {
                b.btnRowAction.setLoading(false, "", ctx.getString(R.string.ml_action_quit_session))
                b.btnRowAction.setOnClickListener { listener.onQuitSession(c.id) }
            } else {
                val label =
                    if (row.trust == MoonlightTrustState.PAIRED) {
                        ctx.getString(R.string.ml_action_pair_again)
                    } else {
                        ctx.getString(R.string.ml_action_pair)
                    }
                b.btnRowAction.setLoading(false, "", label)
                b.btnRowAction.setOnClickListener { listener.onPairKnown(c) }
            }
            b.btnRowSecondary.visibility = View.VISIBLE
            b.btnRowSecondary.text = ctx.getString(R.string.action_forget_short)
            b.btnRowSecondary.setOnClickListener { listener.onForget(c.id) }
        }

        private fun detailFor(row: MoonlightRow.Known): String {
            if (row.controllerCount == 0) return row.summary.detail
            val count =
                ctx.resources.getQuantityString(
                    R.plurals.ml_host_in_use_count,
                    row.controllerCount,
                    row.controllerCount,
                )
            return row.summary.detail + " · " + ctx.getString(R.string.ml_host_in_use, count)
        }

        private fun bindDiscovered(row: MoonlightRow.Discovered) {
            val h = row.host
            b.paintConnection(
                h.name.ifEmpty { h.address },
                ctx.getString(R.string.moonlight_row_detail, h.address),
                ctx.getString(R.string.ml_trust_not_paired),
                ConnectionKind.MOONLIGHT,
                LinkState.Found,
            )
            b.btnRowAction.setLoading(false, "", ctx.getString(R.string.ml_action_pair))
            b.btnRowAction.setOnClickListener { listener.onPairDiscovered(h) }
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
// The trust word is derived from what we already hold: a session that is up proves the pairing
// stands, a stored record means the pairing is remembered but unverified this visit, and
// anything else has never been paired. Nothing here probes; the binding flow does that.
fun moonlightRows(
    conns: List<ConnectionSummary>,
    discovered: List<MoonlightHost>,
    rememberedIds: Set<String> = emptySet(),
): List<MoonlightRow> {
    val known = conns.filter { it.kind == ConnectionKind.MOONLIGHT }
    val knownIds = known.mapTo(mutableSetOf()) { it.id }
    return buildList {
        known.forEach { summary ->
            add(
                MoonlightRow.Known(
                    summary = summary,
                    trust = moonlightTrustFor(summary, summary.id in rememberedIds),
                    controllerCount = summary.boundSlotIds.size,
                ),
            )
        }
        discovered.forEach { host ->
            if (host.id !in knownIds) add(MoonlightRow.Discovered(host))
        }
    }
}

internal fun moonlightTrustFor(
    summary: ConnectionSummary,
    remembered: Boolean,
): MoonlightTrustState =
    when {
        summary.live == LinkState.Connected || summary.live == LinkState.Unstable -> MoonlightTrustState.PAIRED
        remembered -> MoonlightTrustState.REMEMBERED
        else -> MoonlightTrustState.NOT_PAIRED
    }
