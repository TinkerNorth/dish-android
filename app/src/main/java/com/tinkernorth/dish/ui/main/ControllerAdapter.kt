package com.tinkernorth.dish.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.data.model.ControllerEntry
import com.tinkernorth.dish.databinding.ItemControllerBinding

class ControllerAdapter : ListAdapter<ControllerEntry, ControllerAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemControllerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ControllerEntry) {
            binding.tvControllerName.text = item.name
            binding.tvControllerId.text = "ID: ${item.id}"

            if (item.isDisconnected) {
                binding.tvDisconnectCountdown.visibility = View.VISIBLE
                binding.tvDisconnectCountdown.text = "${item.disconnectTimeLeft}s"
                binding.root.alpha = 0.5f
            } else {
                binding.tvDisconnectCountdown.visibility = View.GONE
                binding.root.alpha = 1.0f
            }
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
