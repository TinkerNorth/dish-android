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

internal data class PillSpec(
    val text: String,
    @DrawableRes val icon: Int?,
    val tone: PillTone,
)

private const val PILL_ALPHA_OFF = 0.6f

internal fun BindingPillBinding.bindPill(spec: PillSpec) {
    val fg = root.context.getColor(spec.tone.foreground)
    tvPillText.text = spec.text
    tvPillText.setTextColor(fg)
    root.setBackgroundResource(spec.tone.background)
    if (spec.icon != null) {
        ivPillIcon.visibility = View.VISIBLE
        ivPillIcon.setImageResource(spec.icon)
        ivPillIcon.imageTintList = ColorStateList.valueOf(fg)
    } else {
        ivPillIcon.visibility = View.GONE
    }
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
