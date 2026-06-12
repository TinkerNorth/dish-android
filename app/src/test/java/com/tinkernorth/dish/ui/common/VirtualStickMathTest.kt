// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class VirtualStickMathTest {
    private val max = Short.MAX_VALUE.toInt()
    private val eps = 2 // 1-bit float/rounding slop in int16 result

    @Test
    fun `neutral input (0,0) returns all zero`() {
        val r = computeStickAxes(0f, 0f)
        assertEquals(0f, r.dx, 0f)
        assertEquals(0f, r.dy, 0f)
        assertEquals(0.toShort(), r.axisX)
        assertEquals(0.toShort(), r.axisY)
    }

    @Test
    fun `finger straight up (view dy = -1) sends axisY = +Short_MAX`() {
        // Android view coords are y-down; wire uses Xbox/XInput where stick-up = +Y.
        val r = computeStickAxes(0f, -1f)
        assertNear(max, r.axisY.toInt())
        assertEquals(0.toShort(), r.axisX)
    }

    @Test
    fun `finger straight down (view dy = +1) sends axisY = -Short_MAX`() {
        val r = computeStickAxes(0f, 1f)
        assertNear(-max, r.axisY.toInt())
        assertEquals(0.toShort(), r.axisX)
    }

    @Test
    fun `finger straight right (view dx = +1) sends axisX = +Short_MAX`() {
        val r = computeStickAxes(1f, 0f)
        assertNear(max, r.axisX.toInt())
        assertEquals(0.toShort(), r.axisY)
    }

    @Test
    fun `finger straight left (view dx = -1) sends axisX = -Short_MAX`() {
        val r = computeStickAxes(-1f, 0f)
        assertNear(-max, r.axisX.toInt())
        assertEquals(0.toShort(), r.axisY)
    }

    @Test
    fun `virtual up matches physical up - both yield positive axisY`() {
        val virtualLY = computeStickAxes(0f, -1f).axisY.toInt()
        assertTrue("virtual=$virtualLY must be positive for stick-up", virtualLY > 0)
    }

    @Test
    fun `magnitude beyond unit circle is clamped, direction preserved`() {
        val r = computeStickAxes(2f, 0f)
        assertNear(max, r.axisX.toInt())
        assertEquals(0.toShort(), r.axisY)
        assertEquals(1f, r.dx, 0.001f)
        assertEquals(0f, r.dy, 0.001f)
    }

    @Test
    fun `diagonal beyond unit circle saturates at 45° on the unit circle`() {
        val r = computeStickAxes(2f, -2f)
        val expected = (0.7071f * max).toInt()
        assertNear(expected, r.axisX.toInt(), tolerance = 4)
        assertNear(expected, r.axisY.toInt(), tolerance = 4)
        assertEquals(abs(r.axisX.toInt()), abs(r.axisY.toInt()))
    }

    @Test
    fun `inside unit circle passes through without clamp`() {
        val r = computeStickAxes(0.5f, 0f)
        assertNear(max / 2, r.axisX.toInt(), tolerance = 4)
        assertEquals(0.5f, r.dx, 0.001f)
    }

    @Test
    fun `visual dy preserves view-coords sign (y-down)`() {
        // Visual dx/dy render the thumbstick in view coords; unlike axisY they are NOT inverted.
        val r = computeStickAxes(0f, 1f)
        assertTrue("visual dy should be positive for finger below center", r.dy > 0)
    }

    @Test
    fun `visual magnitude never exceeds 1`() {
        for ((dx, dy) in listOf(5f to 0f, 0f to 5f, -5f to 5f, 3f to -4f)) {
            val r = computeStickAxes(dx, dy)
            assertTrue("hypot(${r.dx},${r.dy}) > 1", hypot(r.dx, r.dy) <= 1.0001f)
        }
    }

    private fun assertNear(
        expected: Int,
        actual: Int,
        tolerance: Int = eps,
    ) {
        assertTrue(
            "expected $expected ± $tolerance, got $actual",
            abs(expected - actual) <= tolerance,
        )
    }
}
