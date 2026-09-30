// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

// A rectangle in view pixels with RectF's shape, kept off android.graphics so the pad's geometry
// can be computed and pinned on the JVM, where RectF is a stub that keeps no coordinates.
internal data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left

    val height: Float get() = bottom - top

    val centerX: Float get() = (left + right) / 2f

    val centerY: Float get() = (top + bottom) / 2f

    // RectF.contains: the right and bottom edges are outside, and an empty box holds nothing.
    fun contains(
        x: Float,
        y: Float,
    ): Boolean {
        val isNotEmpty = left < right && top < bottom
        val insideX = x >= left && x < right
        val insideY = y >= top && y < bottom
        return isNotEmpty && insideX && insideY
    }
}

// The system-bar and display-cutout insets the pad keeps its controls clear of.
internal data class EdgeInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal val NO_INSETS = EdgeInsets(left = 0, top = 0, right = 0, bottom = 0)
