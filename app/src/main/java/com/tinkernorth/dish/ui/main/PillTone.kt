// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.net.DishProtocol
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
    SUCCESS(R.drawable.bg_binding_pill_success, R.color.colorSuccess),
    ERROR(R.drawable.bg_binding_pill_error, R.color.colorError),
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

// Protocol-compat chip, shared by every surface that names a host: soft amber for a
// host that still works at an older protocol, error red when one side must update.
// Null when there is nothing to say (current, or never probed).
internal fun compatPillParts(compat: DishProtocol.Compat): Pair<Int, PillTone>? =
    when (compat) {
        DishProtocol.Compat.SATELLITE_UPDATE_AVAILABLE ->
            R.string.chip_satellite_update_available to PillTone.WARN
        DishProtocol.Compat.SATELLITE_UPDATE_REQUIRED ->
            R.string.chip_satellite_update_required to PillTone.ERROR
        DishProtocol.Compat.APP_UPDATE_REQUIRED ->
            R.string.chip_app_update_required to PillTone.ERROR
        DishProtocol.Compat.UNKNOWN, DishProtocol.Compat.CURRENT -> null
    }

internal fun compatPillSpec(
    context: Context,
    compat: DishProtocol.Compat,
): PillSpec? = compatPillParts(compat)?.let { (text, tone) -> PillSpec(context.getString(text), null, tone) }

// Paints one binding_pill include as the compat chip, or hides it when current/unknown.
internal fun BindingPillBinding.bindCompat(compat: DishProtocol.Compat) {
    val spec = compatPillSpec(root.context, compat)
    if (spec == null) {
        root.visibility = View.GONE
    } else {
        bindPill(spec)
        root.visibility = View.VISIBLE
    }
}
