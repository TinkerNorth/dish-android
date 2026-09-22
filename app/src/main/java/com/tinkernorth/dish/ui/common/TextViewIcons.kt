// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources

// A label and the glyph beside it are one view, not two: the glyph rides along as a
// compound drawable. Compound drawables are drawn at their intrinsic size, and the app's
// glyphs are 24dp vectors used in smaller boxes, so every call names the box to fill.
// A null icon clears the one that is there.
internal fun TextView.setLeadingIcon(
    @DrawableRes icon: Int?,
    @DimenRes sizeRes: Int,
    @ColorInt tint: Int? = null,
) {
    setCompoundDrawablesRelative(sizedIcon(icon, sizeRes, tint), null, null, null)
}

internal fun TextView.setTrailingIcon(
    @DrawableRes icon: Int?,
    @DimenRes sizeRes: Int,
    @ColorInt tint: Int? = null,
) {
    setCompoundDrawablesRelative(null, null, sizedIcon(icon, sizeRes, tint), null)
}

private fun TextView.sizedIcon(
    @DrawableRes icon: Int?,
    @DimenRes sizeRes: Int,
    @ColorInt tint: Int?,
): Drawable? {
    val drawable = icon?.let { AppCompatResources.getDrawable(context, it) }?.mutate() ?: return null
    val size = resources.getDimensionPixelSize(sizeRes)
    drawable.setBounds(0, 0, size, size)
    tint?.let { drawable.setTint(it) }
    return drawable
}
