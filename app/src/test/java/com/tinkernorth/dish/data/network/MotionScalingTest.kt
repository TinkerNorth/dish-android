// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MotionScaling] — the pure rad/s + m/s² → wire-int16
 * conversions and the landscape axis remap behind [PhoneMotionSource].
 *
 * The receiver derives its scale constants (`MOTION_GYRO_SCALE_DEG_S`,
 * `MOTION_ACCEL_SCALE_G`) from the same ±2000 deg/s and ±4 g full scale, so
 * a drift here desyncs both ends silently — these tests pin it.
 */
class MotionScalingTest {
    // ── gyroRadToWire ───────────────────────────────────────────────────────

    @Test
    fun `gyro zero maps to zero`() {
        assertEquals(0, MotionScaling.gyroRadToWire(0f).toInt())
    }

    @Test
    fun `gyro full scale maps to int16 max`() {
        // 2000 deg/s in rad/s = 2000 / (180/pi).
        val fullScaleRad = Math.toRadians(2000.0).toFloat()
        val wire = MotionScaling.gyroRadToWire(fullScaleRad).toInt()
        assertTrue("expected ~32767, got $wire", wire in 32766..32767)
    }

    @Test
    fun `gyro negative full scale maps near int16 min`() {
        val fullScaleRad = Math.toRadians(-2000.0).toFloat()
        val wire = MotionScaling.gyroRadToWire(fullScaleRad).toInt()
        assertTrue("expected ~-32767, got $wire", wire in -32767..-32766)
    }

    @Test
    fun `gyro beyond full scale clamps to int16 range`() {
        val overRad = Math.toRadians(5000.0).toFloat()
        assertEquals(32767, MotionScaling.gyroRadToWire(overRad).toInt())
        assertEquals(-32768, MotionScaling.gyroRadToWire(-overRad).toInt())
    }

    @Test
    fun `gyro quarter scale is roughly a quarter of int16 max`() {
        val quarterRad = Math.toRadians(500.0).toFloat() // 500 of 2000 deg/s
        val wire = MotionScaling.gyroRadToWire(quarterRad).toInt()
        assertTrue("expected ~8192, got $wire", wire in 8150..8240)
    }

    // ── accelMssToWire ──────────────────────────────────────────────────────

    @Test
    fun `accel zero maps to zero`() {
        assertEquals(0, MotionScaling.accelMssToWire(0f).toInt())
    }

    @Test
    fun `accel one g is an eighth of full scale`() {
        // 1 g of 4 g full scale → ~32767 / 4 ≈ 8192.
        val wire = MotionScaling.accelMssToWire(MotionScaling.GRAVITY_MSS.toFloat()).toInt()
        assertTrue("expected ~8192, got $wire", wire in 8150..8240)
    }

    @Test
    fun `accel four g maps to int16 max`() {
        val fourG = (MotionScaling.GRAVITY_MSS * 4).toFloat()
        val wire = MotionScaling.accelMssToWire(fourG).toInt()
        assertTrue("expected ~32767, got $wire", wire in 32766..32767)
    }

    @Test
    fun `accel beyond full scale clamps`() {
        val tenG = (MotionScaling.GRAVITY_MSS * 10).toFloat()
        assertEquals(32767, MotionScaling.accelMssToWire(tenG).toInt())
        assertEquals(-32768, MotionScaling.accelMssToWire(-tenG).toInt())
    }

    // ── remapLandscape ──────────────────────────────────────────────────────

    @Test
    fun `landscape remap rotates device axes into the screen frame`() {
        // Device (x, y, z) → screen (y, -x, z) for ROTATION_90.
        val out = MotionScaling.remapLandscape(1f, 2f, 3f)
        assertEquals(2f, out[0], 0f) // screenX = deviceY
        assertEquals(-1f, out[1], 0f) // screenY = -deviceX
        assertEquals(3f, out[2], 0f) // screenZ = deviceZ
    }

    @Test
    fun `landscape remap leaves the z axis untouched`() {
        val out = MotionScaling.remapLandscape(0f, 0f, 9.8f)
        assertEquals(9.8f, out[2], 0f)
    }
}
