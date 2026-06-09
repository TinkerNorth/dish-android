// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

class StaticViewAdapter(
    @LayoutRes private val layoutRes: Int,
) : RecyclerView.Adapter<StaticViewAdapter.VH>() {
    class VH(
        view: View,
    ) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH = VH(LayoutInflater.from(parent.context).inflate(layoutRes, parent, false))

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
    ) = Unit

    override fun getItemCount(): Int = 1
}
