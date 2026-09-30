// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.graphics.drawable.Animatable
import android.view.View
import com.google.android.material.button.MaterialButton

// The one alpha for anything drawn but not live: a disabled control, a card whose pad is
// unplugging, a step the apply overlay has not reached.
const val DIMMED_ALPHA = 0.4f

private const val SPINNER_TEXT_RATIO = 0.95f
private const val SPINNER_MIN_PX = 16
private const val ICON_PAD_RATIO = 0.4f
private const val ICON_PAD_MIN_PX = 6

fun MaterialButton.setEnabledDimmed(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else DIMMED_ALPHA
}

fun MaterialButton.setLoading(
    loading: Boolean,
    loadingText: String,
    restingText: String,
) {
    if (loading) showLoading(loadingText) else showResting(restingText)
}

private fun MaterialButton.showLoading(loadingText: String) {
    val size = (textSize * SPINNER_TEXT_RATIO).toInt().coerceAtLeast(SPINNER_MIN_PX)
    val spinner = DishSpinnerDrawable(context, size)
    // Clear iconTint so the spinner's brand-cyan ring isn't recoloured by the button's foreground CSL.
    iconTint = null
    icon = spinner
    iconSize = size
    iconPadding = (textSize * ICON_PAD_RATIO).toInt().coerceAtLeast(ICON_PAD_MIN_PX)
    spinner.start()
    text = loadingText
    setEnabledDimmed(false)
}

private fun MaterialButton.showResting(restingText: String) {
    val current = icon
    if (current is Animatable) current.stop()
    icon = null
    text = restingText
    setEnabledDimmed(true)
}

fun View.applyDishDisabledAlpha() {
    alpha = if (isEnabled) 1f else DIMMED_ALPHA
}
