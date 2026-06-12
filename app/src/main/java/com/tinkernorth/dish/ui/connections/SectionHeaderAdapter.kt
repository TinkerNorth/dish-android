// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.SectionHeaderBinding
import com.tinkernorth.dish.ui.common.setLoading

class SectionHeaderAdapter(
    @DrawableRes private val icon: Int,
    @StringRes private val label: Int,
    @StringRes private val actionLabel: Int,
    @StringRes private val secondaryActionLabel: Int? = null,
    private val onSecondaryAction: (() -> Unit)? = null,
    private val onAction: () -> Unit,
) : RecyclerView.Adapter<SectionHeaderAdapter.VH>() {
    private var loading = false
    private var loadingText = ""

    fun setLoading(
        loading: Boolean,
        loadingText: String,
    ) {
        if (this.loading == loading && this.loadingText == loadingText) return
        this.loading = loading
        this.loadingText = loadingText
        notifyItemChanged(0)
    }

    class VH(
        val binding: SectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH {
        val binding = SectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = parent.resources.getDimensionPixelSize(R.dimen.spacing_md)
        }
        return VH(binding)
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
    ) {
        val b = holder.binding
        b.iconSection.visibility = View.VISIBLE
        b.iconSection.setImageResource(icon)
        b.labelSection.setText(label)
        b.btnSectionAction.visibility = View.VISIBLE
        b.btnSectionAction.setLoading(loading, loadingText, b.root.context.getString(actionLabel))
        b.btnSectionAction.setOnClickListener { onAction() }
        val secondary = secondaryActionLabel
        if (secondary != null && onSecondaryAction != null) {
            b.btnSectionSecondary.visibility = View.VISIBLE
            b.btnSectionSecondary.setText(secondary)
            b.btnSectionSecondary.setOnClickListener { onSecondaryAction() }
        } else {
            b.btnSectionSecondary.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = 1
}
