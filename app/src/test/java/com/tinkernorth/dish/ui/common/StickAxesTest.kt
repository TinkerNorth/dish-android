// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Locks the virtual pad's touch-to-axis mapping: magnitude clamp, the y-down to
// stick-up sign flip, and full-deflection wire values.
class StickAxesTest {
    @Test
    fun `center maps to zero on both axes`() {
        val axes = computeStickAxes(0f, 0f)
        assertEquals(0, axes.axisX.toInt())
        assertEquals(0, axes.axisY.toInt())
    }

    @Test
    fun `full right deflection saturates axisX positive`() {
        val axes = computeStickAxes(1f, 0f)
        assertEquals(Short.MAX_VALUE.toInt(), axes.axisX.toInt())
        assertEquals(0, axes.axisY.toInt())
    }

    @Test
    fun `touch up (negative view y) is wire stick-up (positive axisY)`() {
        val axes = computeStickAxes(0f, -1f)
        assertEquals(Short.MAX_VALUE.toInt(), axes.axisY.toInt())
    }

    @Test
    fun `touch down is wire stick-down`() {
        val axes = computeStickAxes(0f, 1f)
        assertTrue(axes.axisY < 0)
    }

    @Test
    fun `a drag past the ring clamps to unit magnitude, direction kept`() {
        val axes = computeStickAxes(3f, -4f)
        val magnitude = kotlin.math.hypot(axes.dx, axes.dy)
        assertEquals(1f, magnitude, 1e-4f)
        assertEquals(0.6f, axes.dx, 1e-4f)
        assertEquals(-0.8f, axes.dy, 1e-4f)
    }

    @Test
    fun `a diagonal keeps both wire axes proportional`() {
        val axes = computeStickAxes(0.5f, -0.5f)
        assertEquals(abs(axes.axisX.toInt()), abs(axes.axisY.toInt()))
        assertTrue(axes.axisX > 0)
        assertTrue(axes.axisY > 0)
    }
}
