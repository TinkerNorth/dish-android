// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.connection.SatelliteConnection
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalMotionSourceTest {
    // filterByCapability reads only the controller layer (the input gyro, live OR static-DB) and
    // userEnabled. gyro -> controller MOTION, userMotion -> userEnabled MOTION; the rest are irrelevant.
    private fun caps(
        gyro: Boolean,
        userMotion: Boolean,
    ): SlotCapabilities {
        fun motionSet(present: Boolean) = if (present) CapabilitySet.of(Feature.MOTION) else CapabilitySet.EMPTY
        val all = CapabilitySet(Feature.entries.toSet())
        return SlotCapabilities(
            controller = motionSet(gyro),
            transport = all,
            type = all,
            host = all,
            userEnabled = motionSet(userMotion),
            runtimeDown = CapabilitySet.EMPTY,
        )
    }

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
        assertEquals(true, s.gyroX.toInt() in 32766..32767)
    }

    @Test
    fun `gyro axes are NOT remapped - identity, unlike the phone path`() {
        val gx = Math.toRadians(200.0).toFloat()
        val gy = Math.toRadians(-600.0).toFloat()
        val gz = Math.toRadians(1000.0).toFloat()
        val s =
            PhysicalMotionSource.convertControllerSample(
                gyroX = gx,
                gyroY = gy,
                gyroZ = gz,
                accelX = 0,
                accelY = 0,
                accelZ = 0,
            )
        assertEquals(MotionScaling.gyroRadToWire(gx), s.gyroX)
        assertEquals(MotionScaling.gyroRadToWire(gy), s.gyroY)
        assertEquals(MotionScaling.gyroRadToWire(gz), s.gyroZ)
        assertEquals(true, s.gyroX > 0)
        assertEquals(true, s.gyroY < 0)
        assertEquals(true, s.gyroZ > 0)
    }

    @Test
    fun `accel triple passes through already-scaled`() {
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

    private fun fakeConn(): SatelliteConnection = mockk(relaxed = true)

    @Test
    fun `filterByCapability keeps a reachable slot that has gyro AND is user-enabled`() {
        val conn = fakeConn()
        val reachable = mapOf("9" to conn)
        val caps = mapOf("9" to caps(gyro = true, userMotion = true))
        assertEquals(reachable, PhysicalMotionSource.filterByCapability(reachable, caps))
    }

    @Test
    fun `filterByCapability drops a reachable slot whose pad has NO gyro`() {
        val reachable = mapOf("9" to fakeConn())
        val caps = mapOf("9" to caps(gyro = false, userMotion = true))
        assertTrue(PhysicalMotionSource.filterByCapability(reachable, caps).isEmpty())
    }

    @Test
    fun `filterByCapability drops a slot the user has toggled motion off for`() {
        val reachable = mapOf("9" to fakeConn())
        val caps = mapOf("9" to caps(gyro = true, userMotion = false))
        assertTrue(PhysicalMotionSource.filterByCapability(reachable, caps).isEmpty())
    }

    @Test
    fun `filterByCapability drops a reachable slot that is missing from caps`() {
        // Startup race: reachability emits before the capability composer. Treat unknown as no-motion (safe).
        val reachable = mapOf("9" to fakeConn())
        val caps = emptyMap<String, SlotCapabilities>()
        assertTrue(PhysicalMotionSource.filterByCapability(reachable, caps).isEmpty())
    }

    @Test
    fun `filterByCapability keeps a static-IMU-DB pad even with no live gyro probe (R3)`() {
        // device.hasGyro=false but native.modelHasImu=true lands MOTION in the controller layer the
        // same way a live probe would, so the IMU starts for a known pad once motion is enabled.
        val conn = fakeConn()
        val reachable = mapOf("9" to conn)
        val caps = mapOf("9" to caps(gyro = true, userMotion = true))
        assertEquals(reachable, PhysicalMotionSource.filterByCapability(reachable, caps))
    }

    @Test
    fun `shouldEmitGyro returns true when the pad has no accelerometer`() {
        // Short-circuit: alternative is an indefinitely silent gyro stream when accel cache stays zero forever.
        assertTrue(PhysicalMotionSource.shouldEmitGyro(hasAccelSensor = false, accelSeen = false))
    }

    @Test
    fun `shouldEmitGyro returns false on the first gyro before accel has reported`() {
        assertFalse(PhysicalMotionSource.shouldEmitGyro(hasAccelSensor = true, accelSeen = false))
    }

    @Test
    fun `shouldEmitGyro returns true once accel has reported`() {
        assertTrue(PhysicalMotionSource.shouldEmitGyro(hasAccelSensor = true, accelSeen = true))
    }

    @Test
    fun `shouldEmitGyro accel-sensor-absent path ignores accelSeen for safety`() {
        assertTrue(PhysicalMotionSource.shouldEmitGyro(hasAccelSensor = false, accelSeen = true))
    }

    @Test
    fun `filterByCapability per-slot - keeps the enabled one, drops the disabled one`() {
        val connA = fakeConn()
        val connB = fakeConn()
        val reachable = mapOf("A" to connA, "B" to connB)
        val caps =
            mapOf(
                "A" to caps(gyro = true, userMotion = true),
                "B" to caps(gyro = true, userMotion = false),
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
