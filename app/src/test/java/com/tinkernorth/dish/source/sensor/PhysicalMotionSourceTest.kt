// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the physical-pad IMU conversion path
 * ([PhysicalMotionSource.convertControllerSample]) — roadmap Task 1.1, step 2.
 *
 * The scaling itself is pinned by [MotionScalingTest]; what these tests pin is
 * the contract specific to a *physical gamepad* IMU: the conversion applies an
 * **identity axis remap** (no landscape rotation), because a controller's IMU
 * is already reported in the controller body frame — right-handed `+X` right /
 * `+Y` up / `+Z` toward the player, the same as the wire. The phone path
 * ([PhoneMotionSource]) rotates; the physical-pad path must not.
 */
class PhysicalMotionSourceTest {
    @Test
    fun `zero gyro maps to zero`() {
        val s =
            PhysicalMotionSource.convertControllerSample(
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 0f,
                accelX = 0,
                accelY = 0,
                accelZ = 0,
            )
        assertEquals(0, s.gyroX.toInt())
        assertEquals(0, s.gyroY.toInt())
        assertEquals(0, s.gyroZ.toInt())
    }

    @Test
    fun `gyro full scale maps to int16 max`() {
        // 2000 deg/s expressed in rad/s — the wire full scale.
        val fullScaleRad = Math.toRadians(2000.0).toFloat()
        val s =
            PhysicalMotionSource.convertControllerSample(
                gyroX = fullScaleRad,
                gyroY = 0f,
                gyroZ = 0f,
                accelX = 0,
                accelY = 0,
                accelZ = 0,
            )
        assertEquals("expected ~32767", true, s.gyroX.toInt() in 32766..32767)
    }

    @Test
    fun `gyro axes are NOT remapped - identity, unlike the phone path`() {
        // Each gyro axis must scale straight through to its OWN wire axis.
        // A landscape remap would swap / negate X and Y; for a physical pad it
        // must not. Use three clearly-different magnitudes so an accidental
        // swap or sign flip would be caught.
        val gx = Math.toRadians(200.0).toFloat() // +X
        val gy = Math.toRadians(-600.0).toFloat() // -Y
        val gz = Math.toRadians(1000.0).toFloat() // +Z
        val s =
            PhysicalMotionSource.convertControllerSample(
                gyroX = gx,
                gyroY = gy,
                gyroZ = gz,
                accelX = 0,
                accelY = 0,
                accelZ = 0,
            )
        // Expected: each axis is gyroRadToWire of its own input — no swap.
        assertEquals(MotionScaling.gyroRadToWire(gx), s.gyroX)
        assertEquals(MotionScaling.gyroRadToWire(gy), s.gyroY)
        assertEquals(MotionScaling.gyroRadToWire(gz), s.gyroZ)
        // X positive, Y negative, Z positive — signs preserved, not flipped.
        assertEquals(true, s.gyroX > 0)
        assertEquals(true, s.gyroY < 0)
        assertEquals(true, s.gyroZ > 0)
    }

    @Test
    fun `accel triple passes through already-scaled`() {
        // The accel callback scales eagerly to wire int16; convertControllerSample
        // must forward the cached triple verbatim, not re-scale or remap it.
        val s =
            PhysicalMotionSource.convertControllerSample(
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 0f,
                accelX = 1234,
                accelY = -5678,
                accelZ = 8191,
            )
        assertEquals(1234, s.accelX.toInt())
        assertEquals(-5678, s.accelY.toInt())
        assertEquals(8191, s.accelZ.toInt())
    }

    @Test
    fun `gyro beyond full scale clamps to the int16 range`() {
        val overRad = Math.toRadians(9000.0).toFloat()
        val s =
            PhysicalMotionSource.convertControllerSample(
                gyroX = overRad,
                gyroY = -overRad,
                gyroZ = 0f,
                accelX = 0,
                accelY = 0,
                accelZ = 0,
            )
        assertEquals(32767, s.gyroX.toInt())
        assertEquals(-32768, s.gyroY.toInt())
    }
}
