// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionScalingTest {
    @Test
    fun `gyro zero maps to zero`() {
        assertEquals(0, MotionScaling.gyroRadToWire(0f).toInt())
    }

    @Test
    fun `gyro full scale maps to int16 max`() {
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
        val quarterRad = Math.toRadians(500.0).toFloat()
        val wire = MotionScaling.gyroRadToWire(quarterRad).toInt()
        assertTrue("expected ~8192, got $wire", wire in 8150..8240)
    }

    @Test
    fun `accel zero maps to zero`() {
        assertEquals(0, MotionScaling.accelMssToWire(0f).toInt())
    }

    @Test
    fun `accel one g is an eighth of full scale`() {
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

    @Test
    fun `landscape remap rotates device axes into the screen frame`() {
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_90)
        assertEquals(2f, out[0], 0f)
        assertEquals(-1f, out[1], 0f)
        assertEquals(3f, out[2], 0f)
    }

    @Test
    fun `landscape remap leaves the z axis untouched`() {
        val out = MotionScaling.remapLandscape(0f, 0f, 9.8f, ROTATION_90)
        assertEquals(9.8f, out[2], 0f)
    }

    @Test
    fun `ROTATION_270 inverts both X and Y - the half-fleet bug`() {
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_270)
        assertEquals(-2f, out[0], 0f)
        assertEquals(1f, out[1], 0f)
        assertEquals(3f, out[2], 0f)
    }

    @Test
    fun `ROTATION_270 is the exact negation of ROTATION_90 in X and Y`() {
        val r90 = MotionScaling.remapLandscape(1.5f, -2.5f, 7f, ROTATION_90)
        val r270 = MotionScaling.remapLandscape(1.5f, -2.5f, 7f, ROTATION_270)
        assertEquals(-r90[0], r270[0], 0f)
        assertEquals(-r90[1], r270[1], 0f)
        assertEquals(r90[2], r270[2], 0f)
    }

    @Test
    fun `ROTATION_0 is the identity remap`() {
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_0)
        assertEquals(1f, out[0], 0f)
        assertEquals(2f, out[1], 0f)
        assertEquals(3f, out[2], 0f)
    }

    @Test
    fun `ROTATION_180 inverts X and Y but not Z`() {
        val out = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_180)
        assertEquals(-1f, out[0], 0f)
        assertEquals(-2f, out[1], 0f)
        assertEquals(3f, out[2], 0f)
    }

    @Test
    fun `unknown rotation falls back to the ROTATION_90 landscape remap`() {
        val fallback = MotionScaling.remapLandscape(1f, 2f, 3f, 99)
        val r90 = MotionScaling.remapLandscape(1f, 2f, 3f, ROTATION_90)
        assertEquals(r90[0], fallback[0], 0f)
        assertEquals(r90[1], fallback[1], 0f)
        assertEquals(r90[2], fallback[2], 0f)
    }

    @Test
    fun `out-param variant writes into the caller's scratch and returns Mapped`() {
        val scratch = FloatArray(3)
        val result =
            MotionScaling.remapLandscape(
                deviceX = 1f,
                deviceY = 2f,
                deviceZ = 3f,
                rotation = ROTATION_90,
                out = scratch,
            )
        assertEquals(MotionScaling.RemapResult.Mapped, result)
        assertEquals(2f, scratch[0], 0f)
        assertEquals(-1f, scratch[1], 0f)
        assertEquals(3f, scratch[2], 0f)
    }

    @Test
    fun `out-param variant returns Fallback with the unknown rotation value`() {
        val scratch = FloatArray(3)
        val result = MotionScaling.remapLandscape(0f, 0f, 0f, rotation = 99, out = scratch)
        assertEquals(MotionScaling.RemapResult.Fallback(99), result)
        val r90 = MotionScaling.remapLandscape(0f, 0f, 0f, ROTATION_90)
        assertEquals(r90[0], scratch[0], 0f)
        assertEquals(r90[1], scratch[1], 0f)
        assertEquals(r90[2], scratch[2], 0f)
    }

    @Test
    fun `out-param variant rejects a scratch smaller than 3 elements`() {
        val tooSmall = FloatArray(2)
        try {
            MotionScaling.remapLandscape(0f, 0f, 0f, ROTATION_90, tooSmall)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("3") == true)
        }
    }

    @Test
    fun `out-param variant produces the same triple as the allocating shim`() {
        for (rot in intArrayOf(ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270, 99)) {
            val out = FloatArray(3)
            MotionScaling.remapLandscape(1.25f, -3.5f, 0.75f, rot, out)
            val viaShim = MotionScaling.remapLandscape(1.25f, -3.5f, 0.75f, rot)
            assertEquals("rot=$rot X", viaShim[0], out[0], 0f)
            assertEquals("rot=$rot Y", viaShim[1], out[1], 0f)
            assertEquals("rot=$rot Z", viaShim[2], out[2], 0f)
        }
    }

    private companion object {
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3
    }
}
