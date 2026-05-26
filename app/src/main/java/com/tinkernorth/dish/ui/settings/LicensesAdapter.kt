// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ItemLicenseBinding

class LicensesAdapter(
    private val items: List<LicenseEntry>,
    private val onClick: (LicenseEntry) -> Unit,
) : RecyclerView.Adapter<LicensesAdapter.VH>() {
    class VH(
        val binding: ItemLicenseBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH {
        val binding = ItemLicenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
    ) {
        val entry = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvLibraryName.text =
            entry.name?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(entry.group, entry.artifact).joinToString(":")
        holder.binding.tvLibraryVersion.text = entry.version.orEmpty()

        val licenseName =
            entry.licenses
                .firstOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
        if (licenseName != null) {
            holder.binding.tvLicense.visibility = View.VISIBLE
            holder.binding.tvLicense.text = licenseName
        } else {
            holder.binding.tvLicense.visibility = View.GONE
        }

        val clickUrl = entry.licenses.firstOrNull()?.url ?: entry.url
        if (!clickUrl.isNullOrBlank()) {
            holder.itemView.isClickable = true
            holder.itemView.isFocusable = true
            holder.itemView.contentDescription =
                ctx.getString(R.string.licenses_open_external, holder.binding.tvLibraryName.text)
            holder.itemView.setOnClickListener { onClick(entry) }
        } else {
            holder.itemView.isClickable = false
            holder.itemView.isFocusable = false
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size
}
