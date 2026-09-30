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
import com.tinkernorth.dish.ui.main.chipTextRes

interface MoonlightRowListener {
    fun onPairKnown(summary: ConnectionSummary)

    fun onPairDiscovered(host: MoonlightHost)

    fun onQuitSession(id: String)

    fun onForget(id: String)
}

class MoonlightListAdapter(
    private val listener: MoonlightRowListener,
) : ListAdapter<MoonlightRow, RecyclerView.ViewHolder>(MoonlightRowDiff()) {
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

        // Pairing is remembered trust, not a live link, so the chip carries the trust word
        // and never lights up as though the host were online. The session lives in the
        // binding; all this screen offers is the way out of one.
        private fun bindKnown(row: MoonlightRow.Known) {
            val c = row.summary
            val copy = moonlightRowCopy(row)
            b.paintConnection(c.label, detailFor(row, copy), ctx.getString(row.trust.chipTextRes()), ConnectionKind.MOONLIGHT, c.live)
            b.tvRowStatus.setTextColor(ctx.getColor(moonlightTrustColorRes(row.trust)))
            b.btnRowAction.setLoading(false, "", ctx.getString(copy.primaryLabelRes))
            if (copy.inUse) {
                b.btnRowAction.setOnClickListener { listener.onQuitSession(c.id) }
            } else {
                b.btnRowAction.setOnClickListener { listener.onPairKnown(c) }
            }
            b.btnRowSecondary.visibility = View.VISIBLE
            b.btnRowSecondary.text = ctx.getString(R.string.action_forget_short)
            b.btnRowSecondary.setOnClickListener { listener.onForget(c.id) }
        }

        private fun detailFor(
            row: MoonlightRow.Known,
            copy: MoonlightRowCopy,
        ): String {
            if (!copy.inUse) return row.summary.detail
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
            b.tvRowStatus.setTextColor(ctx.getColor(R.color.colorMuted))
            b.btnRowAction.setLoading(false, "", ctx.getString(R.string.ml_action_pair))
            b.btnRowAction.setOnClickListener { listener.onPairDiscovered(h) }
            b.btnRowSecondary.visibility = View.GONE
            b.btnRowSecondary.setOnClickListener(null)
        }
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_EMPTY = 1
    }
}

private class MoonlightRowDiff : DiffUtil.ItemCallback<MoonlightRow>() {
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
