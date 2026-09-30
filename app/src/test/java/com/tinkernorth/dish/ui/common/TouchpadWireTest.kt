// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

// One normalisation for both screen surfaces: the touchpad view (view-relative) and the pad's
// trackpad zone (rect-relative).
class TouchpadWireTest {
    @Test
    fun `the near edge is the wire's minimum`() {
        assertEquals(Short.MIN_VALUE, spanToWire(offset = 0f, span = SPAN))
    }

    @Test
    fun `the far edge is the wire's maximum`() {
        assertEquals(Short.MAX_VALUE, spanToWire(offset = SPAN, span = SPAN))
    }

    @Test
    fun `the midpoint lands on zero`() {
        assertEquals(0.toShort(), spanToWire(offset = SPAN / 2f, span = SPAN))
    }

    @Test
    fun `a quarter of the way in is a quarter of the way up the wire`() {
        assertEquals(QUARTER_WIRE, spanToWire(offset = SPAN / 4f, span = SPAN))
    }

    @Test
    fun `a finger past the far edge pins to the far edge`() {
        assertEquals(Short.MAX_VALUE, spanToWire(offset = SPAN * 3f, span = SPAN))
    }

    @Test
    fun `a finger past the near edge pins to the near edge`() {
        assertEquals(Short.MIN_VALUE, spanToWire(offset = -SPAN, span = SPAN))
    }

    @Test
    fun `a zero-sized span does not divide by zero`() {
        assertEquals(Short.MIN_VALUE, spanToWire(offset = 0f, span = 0f))
        assertEquals(Short.MAX_VALUE, spanToWire(offset = 1f, span = 0f))
    }

    private companion object {
        const val SPAN = 200f

        // 0.25 * 65535 - 32768, rounded.
        const val QUARTER_WIRE: Short = -16384
    }
}
