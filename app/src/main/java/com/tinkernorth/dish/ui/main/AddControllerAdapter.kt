// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.databinding.ItemControllerAddBinding

// Single-row adapter concatenated after ControllerAdapter so the "Add a controller"
// invite is always the last item (and the only one on first run), scrolling with the
// list rather than pinned below it.
class AddControllerAdapter(
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<AddControllerAdapter.VH>() {
    inner class VH(
        binding: ItemControllerAddBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onAdd() }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH = VH(ItemControllerAddBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
    ) = Unit

    override fun getItemCount(): Int = 1
}
