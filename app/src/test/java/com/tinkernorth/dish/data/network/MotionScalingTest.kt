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
    //
    // rotation arguments are raw Surface.ROTATION_* int values:
    //   ROTATION_0 = 0, ROTATION_90 = 1, ROTATION_180 = 2, ROTATION_270 = 3.

    @Test
    fun `landscape remap rotates device axes into the screen frame`() {
        // Device (x, y, z) → screen (y, -x, z) for ROTATION_90.
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_90)
        assertEquals(2f, out[0], 0f) // screenX = deviceY
        assertEquals(-1f, out[1], 0f) // screenY = -deviceX
        assertEquals(3f, out[2], 0f) // screenZ = deviceZ
    }

    @Test
    fun `landscape remap leaves the z axis untouched`() {
        val out = MotionScaling.remapLandscape(0f, 0f, 9.8f, ROTATION_90)
        assertEquals(9.8f, out[2], 0f)
    }

    @Test
    fun `ROTATION_270 inverts both X and Y - the half-fleet bug`() {
        // The regression case: landscape that resolved to ROTATION_270 must
        // map device (x, y, z) → screen (-y, x, z), i.e. 180° from ROTATION_90.
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_270)
        assertEquals(-2f, out[0], 0f) // screenX = -deviceY
        assertEquals(1f, out[1], 0f) // screenY = deviceX
        assertEquals(3f, out[2], 0f) // screenZ = deviceZ
    }

    @Test
    fun `ROTATION_270 is the exact negation of ROTATION_90 in X and Y`() {
        val r90 = MotionScaling.remapLandscape(1.5f, -2.5f, 7f, ROTATION_90)
        val r270 = MotionScaling.remapLandscape(1.5f, -2.5f, 7f, ROTATION_270)
        assertEquals(-r90[0], r270[0], 0f)
        assertEquals(-r90[1], r270[1], 0f)
        assertEquals(r90[2], r270[2], 0f) // Z is shared, never inverted.
    }

    @Test
    fun `ROTATION_0 is the identity remap`() {
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_0)
        assertEquals(1f, out[0], 0f) // screenX = deviceX
        assertEquals(2f, out[1], 0f) // screenY = deviceY
        assertEquals(3f, out[2], 0f) // screenZ = deviceZ
    }

    @Test
    fun `ROTATION_180 inverts X and Y but not Z`() {
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_180)
        assertEquals(-1f, out[0], 0f) // screenX = -deviceX
        assertEquals(-2f, out[1], 0f) // screenY = -deviceY
        assertEquals(3f, out[2], 0f) // screenZ = deviceZ
    }

    @Test
    fun `unknown rotation falls back to the ROTATION_90 landscape remap`() {
        val fallback = MotionScaling.remapLandscape(1f, 2f, 3f, 99)
        val r90 = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_90)
        assertEquals(r90[0], fallback[0], 0f)
        assertEquals(r90[1], fallback[1], 0f)
        assertEquals(r90[2], fallback[2], 0f)
    }

    private companion object {
        // Mirror of android.view.Surface.ROTATION_* — kept literal so this
        // test stays a pure JVM test with no Android framework dependency.
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3
    }
}
