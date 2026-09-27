// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import kotlin.math.max
import kotlin.math.roundToInt

// The touch wire's signed int16 span: 0 -> -32768 at the near edge, the far edge -> 32767.
internal const val HALF_INT16 = 32768

internal const val NORM_INT16_SPAN = 65535f

// A fresh contact takes the next tracking id; the receiver only ever compares them for equality.
internal const val TRACKING_ID_WRAP_MASK = 0xFF

private const val MIN_SPAN = 1f

// A finger's offset along a span of screen pixels as the wire's int16 coordinate, clamped to the
// span so a drag past either edge pins to that edge, and never dividing by a zero-sized span.
internal fun spanToWire(
    offset: Float,
    span: Float,
): Short {
    val safeSpan = max(span, MIN_SPAN)
    val unit = offset.coerceIn(0f, safeSpan) / safeSpan
    return (unit * NORM_INT16_SPAN - HALF_INT16)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
}
