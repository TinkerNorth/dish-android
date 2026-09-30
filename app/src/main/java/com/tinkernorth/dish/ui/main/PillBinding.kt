// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.databinding.BindingPillBinding
import com.tinkernorth.dish.ui.common.setLeadingIcon

private const val PILL_ALPHA_OFF = 0.6f

internal fun BindingPillBinding.bindPill(spec: PillSpec) {
    val fg = root.context.getColor(spec.tone.foreground)
    tvPillText.text = spec.text
    tvPillText.setTextColor(fg)
    root.setBackgroundResource(spec.tone.background)
    tvPillText.setLeadingIcon(spec.icon, R.dimen.binding_pill_icon_size, fg)
    root.alpha = if (spec.tone == PillTone.OFF) PILL_ALPHA_OFF else 1f
}

internal fun ViewGroup.inflateBindingPill(
    text: String,
    @DrawableRes icon: Int?,
    tone: PillTone,
): View {
    val b = BindingPillBinding.inflate(LayoutInflater.from(context), this, false)
    b.bindPill(PillSpec(text, icon, tone))
    return b.root
}

internal fun compatPillSpec(
    context: Context,
    compat: DishProtocolCompat,
): PillSpec? = compatPillParts(compat)?.let { (text, tone) -> PillSpec(context.getString(text), null, tone) }

// Paints one binding_pill include as the compat chip, or hides it when current/unknown.
internal fun BindingPillBinding.bindCompat(compat: DishProtocolCompat) {
    val spec = compatPillSpec(root.context, compat)
    if (spec == null) {
        root.visibility = View.GONE
    } else {
        bindPill(spec)
        root.visibility = View.VISIBLE
    }
}
