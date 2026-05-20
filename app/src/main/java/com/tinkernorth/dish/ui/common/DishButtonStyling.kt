// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * Apply the design-spec disabled treatment to a Material button.
 *
 * Spec reference: `ds-components.jsx` Button component (lines 36–37,
 * `cursor: 'not-allowed', opacity: disabled ? 0.4 : 1`). The Swift twin is
 * `DishOutlinedButtonStyle` in dish-mac's `Theme.swift`, which reads
 * `@Environment(\.isEnabled)` and applies `.opacity(0.4)` when disabled.
 *
 * Material's default disabled treatment drops the text/background to a
 * ~38% alpha derived from `colorOnSurface` — close to but not the same as
 * the Dish design-system 0.4. Using `View.setAlpha` on top of the style
 * makes the disabled state pixel-faithful to the design across every screen.
 *
 * This is the single funnel for "set enabled, also dim" so the entire app
 * stays consistent. Callers that want the in-button loader pattern call
 * [setLoading] instead, which combines this with the spinner icon swap.
 */
fun MaterialButton.setEnabledDimmed(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.4f
}

/**
 * Toggle the in-button loader. When [loading] is true the button is
 * disabled with the spec's 0.4 alpha and shows a [DishSpinnerDrawable] as
 * its leading icon plus an optional [loadingText]. When false the icon is
 * removed and the button is restored to its [restingText].
 *
 * The dish-mac reference (`ConnectionsView.swift`) puts the spinner *inside*
 * the in-flight button so disabled-state and "working"-state read as one
 * thing. Mirroring that here with `MaterialButton.setIcon` means the loader
 * lives in the same visual rectangle as the label — there's no separate
 * row-level ProgressBar to keep in sync.
 *
 * Note: this function recreates the spinner drawable on every state flip,
 * which is fine because the underlying ValueAnimator is lightweight. Callers
 * in a hot RecyclerView path can cache the drawable instance themselves if
 * the allocations show up in a profile, but for once-per-state-change use on
 * the Connections screen this is the simplest contract.
 */
fun MaterialButton.setLoading(
    loading: Boolean,
    loadingText: String,
    restingText: String,
) {
    if (loading) {
        val size = (textSize * 0.95f).toInt().coerceAtLeast(16)
        val spinner = DishSpinnerDrawable(context, size)
        // Cyan-tinted ink: MaterialButton's iconTint defaults to a CSL
        // derived from the text colour, which would recolour the spinner to
        // whatever the button's foreground is (white-ish on the filled
        // variant). Clear it so the drawable's own brand-cyan ring shows
        // through, matching the dish-mac DishSpinner that reads its colour
        // from DishTheme.primary regardless of host button style.
        iconTint = null
        icon = spinner
        iconSize = size
        iconPadding = (textSize * 0.4f).toInt().coerceAtLeast(6)
        spinner.start()
        text = loadingText
        setEnabledDimmed(false)
    } else {
        val current = icon
        if (current is android.graphics.drawable.Animatable) current.stop()
        icon = null
        text = restingText
        setEnabledDimmed(true)
    }
}

/**
 * Convenience for the disable-without-spinner cases: the BT row's
 * Acquiring/Waiting/Pair-from-host states show a label that already reads
 * as "in flight" but don't want the spinner because the wait is host-driven
 * (we can't shorten it; surfacing motion would imply we can). Same alpha
 * rule, no icon.
 */
@Suppress("unused")
fun View.applyDishDisabledAlpha() {
    alpha = if (isEnabled) 1f else 0.4f
}
