// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Box stands in for RectF on the JVM, so it has to hit-test exactly the way RectF.contains does.
class GamepadGeometryTest {
    private val box = Box(left = 10f, top = 20f, right = 30f, bottom = 60f)

    @Test
    fun `width, height and centre read like RectF`() {
        assertEquals(20f, box.width, 0f)
        assertEquals(40f, box.height, 0f)
        assertEquals(20f, box.centerX, 0f)
        assertEquals(40f, box.centerY, 0f)
    }

    @Test
    fun `the left and top edges are inside`() {
        assertTrue(box.contains(10f, 20f))
        assertTrue(box.contains(10f, 40f))
        assertTrue(box.contains(20f, 20f))
    }

    @Test
    fun `the right and bottom edges are outside, like RectF`() {
        assertFalse(box.contains(30f, 40f))
        assertFalse(box.contains(20f, 60f))
    }

    @Test
    fun `a point outside on any side is outside`() {
        assertFalse(box.contains(9f, 40f))
        assertFalse(box.contains(31f, 40f))
        assertFalse(box.contains(20f, 19f))
        assertFalse(box.contains(20f, 61f))
    }

    @Test
    fun `an empty box holds nothing, not even its own corner`() {
        assertFalse(Box(left = 10f, top = 20f, right = 10f, bottom = 20f).contains(10f, 20f))
        assertFalse(Box(left = 30f, top = 20f, right = 10f, bottom = 60f).contains(20f, 40f))
    }
}
