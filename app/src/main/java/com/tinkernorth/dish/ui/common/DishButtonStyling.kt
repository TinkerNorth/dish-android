// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.view.View
import com.google.android.material.button.MaterialButton

fun MaterialButton.setEnabledDimmed(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.4f
}

fun MaterialButton.setLoading(
    loading: Boolean,
    loadingText: String,
    restingText: String,
) {
    if (loading) {
        val size = (textSize * 0.95f).toInt().coerceAtLeast(16)
        val spinner = DishSpinnerDrawable(context, size)
        // Clear iconTint so the spinner's brand-cyan ring isn't recoloured by the button's foreground CSL.
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

@Suppress("unused")
fun View.applyDishDisabledAlpha() {
    alpha = if (isEnabled) 1f else 0.4f
}
