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
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.statusChipText

data class BtRowUi(
    val summary: ConnectionSummary,
    val connectingLabel: String?,
    val secondaryIsForget: Boolean,
)

sealed interface BluetoothRow {
    data class Item(
        val ui: BtRowUi,
    ) : BluetoothRow

    data class Empty(
        val message: String,
    ) : BluetoothRow
}

interface BluetoothRowListener {
    fun onConnect(id: String)

    fun onDisconnect(id: String)

    fun onRepair(id: String)

    fun onSecondary(summary: ConnectionSummary)
}

class BluetoothListAdapter(
    private val listener: BluetoothRowListener,
) : ListAdapter<BluetoothRow, RecyclerView.ViewHolder>(Diff) {
    override fun getItemViewType(position: Int): Int = if (getItem(position) is BluetoothRow.Empty) TYPE_EMPTY else TYPE_ROW

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
            is BluetoothRow.Empty -> (holder as EmptyVH).bind(row.message)
            is BluetoothRow.Item -> (holder as RowVH).bind(row.ui)
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
        private val listener: BluetoothRowListener,
    ) : RecyclerView.ViewHolder(b.root) {
        private val ctx get() = b.root.context

        fun bind(ui: BtRowUi) {
            val c = ui.summary
            b.paintConnection(c.label, c.detail, statusChipText(ctx, c.live), ConnectionKind.BLUETOOTH, c.live)
            when (c.live) {
                LinkState.Connected, LinkState.Unstable -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_disconnect))
                    b.btnRowAction.setOnClickListener { listener.onDisconnect(c.id) }
                }
                LinkState.Connecting -> {
                    b.btnRowAction.setLoading(true, ui.connectingLabel.orEmpty(), ctx.getString(R.string.action_connect))
                    b.btnRowAction.setOnClickListener(null)
                }
                LinkState.Stale -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_repair_short))
                    b.btnRowAction.setOnClickListener { listener.onRepair(c.id) }
                }
                LinkState.Saved, LinkState.Ready, LinkState.Found -> {
                    b.btnRowAction.setLoading(false, "", ctx.getString(R.string.action_connect))
                    b.btnRowAction.setOnClickListener { listener.onConnect(c.id) }
                }
            }
            b.btnRowSecondary.visibility = View.VISIBLE
            b.btnRowSecondary.text =
                ctx.getString(if (ui.secondaryIsForget) R.string.action_forget_short else R.string.action_cancel)
            b.btnRowSecondary.setOnClickListener { listener.onSecondary(c) }
        }
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_EMPTY = 1

        private val Diff =
            object : DiffUtil.ItemCallback<BluetoothRow>() {
                override fun areItemsTheSame(
                    o: BluetoothRow,
                    n: BluetoothRow,
                ): Boolean =
                    when {
                        o is BluetoothRow.Item && n is BluetoothRow.Item -> o.ui.summary.id == n.ui.summary.id
                        o is BluetoothRow.Empty && n is BluetoothRow.Empty -> true
                        else -> false
                    }

                override fun areContentsTheSame(
                    o: BluetoothRow,
                    n: BluetoothRow,
                ): Boolean = o == n
            }
    }
}
