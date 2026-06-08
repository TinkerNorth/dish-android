// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.BindingPillBinding

internal enum class PillTone(
    @DrawableRes val background: Int,
    @ColorRes val foreground: Int,
) {
    FACT(R.drawable.bg_binding_pill_fact, R.color.colorOnSurface),
    ON(R.drawable.bg_binding_pill_on, R.color.colorPrimary),
    WARN(R.drawable.bg_binding_pill_warn, R.color.colorTertiary),
    CAP(R.drawable.bg_binding_pill_cap, R.color.colorOnSurfaceVariant),
    OFF(R.drawable.bg_binding_pill_off, R.color.colorMuted),
}

private const val PILL_ALPHA_OFF = 0.6f

internal fun ViewGroup.inflateBindingPill(
    text: String,
    @DrawableRes icon: Int?,
    tone: PillTone,
): View {
    val b = BindingPillBinding.inflate(LayoutInflater.from(context), this, false)
    b.tvPillText.text = text
    b.root.setBackgroundResource(tone.background)
    val fg = context.getColor(tone.foreground)
    b.tvPillText.setTextColor(fg)
    if (icon != null) {
        b.ivPillIcon.setImageResource(icon)
        b.ivPillIcon.imageTintList = ColorStateList.valueOf(fg)
    } else {
        b.ivPillIcon.visibility = View.GONE
    }
    b.root.alpha = if (tone == PillTone.OFF) PILL_ALPHA_OFF else 1f
    return b.root
}
