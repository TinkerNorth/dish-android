// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.source.connection.SatelliteConnection
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── filterByCapability — the gate added in PR3 ─────────────────────────

    private fun fakeConn(): SatelliteConnection = mockk(relaxed = true)

    @Test
    fun `filterByCapability keeps a reachable slot that has gyro AND is user-enabled`() {
        // The success path — both capability axes true, the slot stays in
        // the reachable map and its sensor listener will register.
        val conn = fakeConn()
        val reachable = mapOf("9" to conn)
        val caps = mapOf("9" to MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = true))
        assertEquals(reachable, PhysicalMotionSource.filterByCapability(reachable, caps))
    }

    @Test
    fun `filterByCapability drops a reachable slot whose pad has NO gyro`() {
        // A cheap Bluetooth pad on API 31+ exposes the per-device
        // SensorManager but reports no TYPE_GYROSCOPE. Reachability still
        // says "the slot is bound and the satellite is up"; the capability
        // gate must drop it so listening doesn't burn battery on a sensor
        // that will never fire.
        val reachable = mapOf("9" to fakeConn())
        val caps = mapOf("9" to MotionCapability(hasGyro = false, carriesOnConnection = true, userEnabled = true))
        assertTrue(PhysicalMotionSource.filterByCapability(reachable, caps).isEmpty())
    }

    @Test
    fun `filterByCapability drops a slot the user has toggled motion off for`() {
        // The user-facing toggle is the headline PR2/PR3 deliverable —
        // when off, the listener must NOT register, even though hardware
        // and link are both ready. A regression that ignores userEnabled
        // would leak gyro listeners on slots motion is off for, defeating
        // the toggle.
        val reachable = mapOf("9" to fakeConn())
        val caps = mapOf("9" to MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = false))
        assertTrue(PhysicalMotionSource.filterByCapability(reachable, caps).isEmpty())
    }

    @Test
    fun `filterByCapability drops a reachable slot that is missing from caps`() {
        // Startup race: the reachability flow emits before the capability
        // composer's first emission. Treat the unknown slot as "no motion"
        // (safe default) — the next emission will reinstate it if it
        // actually has gyro + is enabled.
        val reachable = mapOf("9" to fakeConn())
        val caps = emptyMap<String, MotionCapability>()
        assertTrue(PhysicalMotionSource.filterByCapability(reachable, caps).isEmpty())
    }

    @Test
    fun `filterByCapability per-slot — keeps the enabled one, drops the disabled one`() {
        // Two pads, same satellite, different user toggles. Pin that the
        // filter is per-slot and not "all-or-nothing."
        val connA = fakeConn()
        val connB = fakeConn()
        val reachable = mapOf("A" to connA, "B" to connB)
        val caps =
            mapOf(
                "A" to MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = true),
                "B" to MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = false),
            )
        val result = PhysicalMotionSource.filterByCapability(reachable, caps)
        assertEquals(mapOf("A" to connA), result)
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
