// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tinkernorth.dish.databinding.DiagnosticsBodyRowBinding
import com.tinkernorth.dish.databinding.DiagnosticsCardBinding
import com.tinkernorth.dish.databinding.DiagnosticsEmptyRowBinding

internal fun ViewGroup.bodyRow(text: String): View =
    DiagnosticsBodyRowBinding.inflate(LayoutInflater.from(context), this, false).root.apply { this.text = text }

internal fun ViewGroup.emptyRow(text: String): View =
    DiagnosticsEmptyRowBinding.inflate(LayoutInflater.from(context), this, false).root.apply { this.text = text }

internal fun ViewGroup.renderLines(
    lines: List<String>,
    emptyText: String? = null,
) {
    removeAllViews()
    if (lines.isEmpty()) {
        emptyText?.let { addView(emptyRow(it)) }
        return
    }
    lines.forEach { addView(bodyRow(it)) }
}

internal fun ViewGroup.card(
    title: String,
    lines: List<String>,
    footer: ((ViewGroup) -> View)? = null,
): View {
    val card = DiagnosticsCardBinding.inflate(LayoutInflater.from(context), this, false)
    card.diagCardTitle.text = title
    lines.forEach { card.diagCardColumn.addView(card.diagCardColumn.bodyRow(it)) }
    footer?.let { card.diagCardColumn.addView(it(card.diagCardColumn)) }
    return card.root
}
