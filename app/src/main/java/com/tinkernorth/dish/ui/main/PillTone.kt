// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.net.DishProtocolCompat

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

// Protocol-compat chip, shared by every surface that names a host: soft amber for a
// host that still works at an older protocol, error red when one side must update.
// Null when there is nothing to say (current, or never probed).
internal fun compatPillParts(compat: DishProtocolCompat): Pair<Int, PillTone>? =
    when (compat) {
        DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE ->
            R.string.chip_satellite_update_available to PillTone.WARN
        DishProtocolCompat.SATELLITE_UPDATE_REQUIRED ->
            R.string.chip_satellite_update_required to PillTone.ERROR
        DishProtocolCompat.APP_UPDATE_REQUIRED ->
            R.string.chip_app_update_required to PillTone.ERROR
        DishProtocolCompat.UNKNOWN, DishProtocolCompat.CURRENT -> null
    }
