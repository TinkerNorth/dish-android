// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.ui.main.scaleAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Contract tests for [computeStickAxes] — the virtual-stick counterpart of
 * the physical gamepad's axis processing.
 *
 * These tests pin two things the app depends on:
 *
 *  1. The **Xbox/XInput Y-axis convention**: "stick up" maps to
 *     +Short.MAX_VALUE on the wire, even though Android view coordinates
 *     are y-down. This is the bug fix that resolved the "Y-axis feels
 *     inverted on the virtual pad but not the physical pad" complaint;
 *     the regression tests below must stay green.
 *  2. **Magnitude clamping preserves direction**: a finger dragged outside
 *     the stick well saturates the axes in that direction but never inverts
 *     them or leaks onto the opposite axis.
 */
class VirtualStickMathTest {
    private val max = Short.MAX_VALUE.toInt() // 32767
    private val eps = 2 // allow 1-bit float/rounding slop in the int16 result

    // ── Neutral ───────────────────────────────────────────────────────────

    @Test
    fun `neutral input (0,0) returns all zero`() {
        val r = computeStickAxes(0f, 0f)
        assertEquals(0f, r.dx, 0f)
        assertEquals(0f, r.dy, 0f)
        assertEquals(0.toShort(), r.axisX)
        assertEquals(0.toShort(), r.axisY)
    }

    // ── Y-axis sign convention (regression for the "weird Y" bug) ─────────

    @Test
    fun `finger straight up (view dy = -1) sends axisY = +Short_MAX`() {
        // Android view coords are y-down, so "up on screen" = negative dy.
        // The wire expects Xbox/XInput convention where stick-up = +Y.
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

    // ── X-axis sign convention (no inversion) ─────────────────────────────

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

    // ── Consistency with the physical path ────────────────────────────────

    @Test
    fun `virtual up matches physical up — both yield positive axisY`() {
        // Physical path (GamepadInputProcessor.processJoystickInput) scales
        // raw AXIS_Y with -AXIS_MAX; Android's AXIS_Y = -1.0 for "stick up",
        // so the physical path emits +32767 for stick up. The virtual path
        // must produce the same sign for the analogous gesture (finger at
        // top of stick well, view dy = -1).
        val physicalLY = scaleAxis(-1f, -32767f) // see GamepadInputProcessor
        val virtualLY = computeStickAxes(0f, -1f).axisY.toInt()
        assertTrue(
            "physical=$physicalLY virtual=$virtualLY — both must be positive for stick-up",
            physicalLY > 0 && virtualLY > 0,
        )
    }

    // ── Magnitude clamping ────────────────────────────────────────────────

    @Test
    fun `magnitude beyond unit circle is clamped, direction preserved`() {
        // (2, 0) is right at 2× the stick radius — clamp to (1, 0).
        val r = computeStickAxes(2f, 0f)
        assertNear(max, r.axisX.toInt())
        assertEquals(0.toShort(), r.axisY)
        assertEquals(1f, r.dx, 0.001f)
        assertEquals(0f, r.dy, 0.001f)
    }

    @Test
    fun `diagonal beyond unit circle saturates at 45° on the unit circle`() {
        val r = computeStickAxes(2f, -2f) // upper-right, past the well
        // On the unit circle: (cos45°, sin45°) ≈ (0.7071, 0.7071)
        val expected = (0.7071f * max).toInt()
        assertNear(expected, r.axisX.toInt(), tolerance = 4)
        assertNear(expected, r.axisY.toInt(), tolerance = 4) // -dy sign flip
        // |axisX| and |axisY| should be equal (same angle).
        assertEquals(abs(r.axisX.toInt()), abs(r.axisY.toInt()))
    }

    @Test
    fun `inside unit circle passes through without clamp`() {
        val r = computeStickAxes(0.5f, 0f)
        assertNear(max / 2, r.axisX.toInt(), tolerance = 4)
        assertEquals(0.5f, r.dx, 0.001f)
    }

    // ── Visual dx,dy contract (used for rendering) ────────────────────────

    @Test
    fun `visual dy preserves view-coords sign (y-down)`() {
        // The dx/dy on the result are for drawing the thumbstick graphic at
        // the finger's clamped position in view coords; unlike axisY, these
        // are NOT inverted.
        val r = computeStickAxes(0f, 1f) // finger below the center
        assertTrue("visual dy should be positive for finger below center", r.dy > 0)
    }

    @Test
    fun `visual magnitude never exceeds 1`() {
        for ((dx, dy) in listOf(5f to 0f, 0f to 5f, -5f to 5f, 3f to -4f)) {
            val r = computeStickAxes(dx, dy)
            assertTrue("hypot(${r.dx},${r.dy}) > 1", hypot(r.dx, r.dy) <= 1.0001f)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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
