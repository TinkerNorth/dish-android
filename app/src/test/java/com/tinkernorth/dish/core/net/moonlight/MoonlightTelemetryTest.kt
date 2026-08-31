// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightTelemetryTest {
    @Test
    fun `gyro wire scale maps full range to 2000 deg per second`() {
        assertEquals(2000.0f, MoonlightTelemetry.gyroDegS(32767), 0.001f)
        assertEquals(-2000.0f, MoonlightTelemetry.gyroDegS(-32767), 0.001f)
        assertEquals(0.0f, MoonlightTelemetry.gyroDegS(0), 0.0f)
        // 1 deg/s = 32767/2000 wire units.
        assertEquals(1.0f, MoonlightTelemetry.gyroDegS(16), 0.05f)
    }

    @Test
    fun `accel wire scale maps full range to 4 g in meters per second squared`() {
        assertEquals(4 * 9.80665f, MoonlightTelemetry.accelMs2(32767), 0.001f)
        assertEquals(-4 * 9.80665f, MoonlightTelemetry.accelMs2(-32767), 0.001f)
        // 1 g = 8191.75 wire units.
        assertEquals(9.80665f, MoonlightTelemetry.accelMs2(8192), 0.01f)
    }

    @Test
    fun `touch coordinates normalize the full int16 range onto 0 to 1`() {
        assertEquals(0.0f, MoonlightTelemetry.touchNorm(Short.MIN_VALUE), 0.0f)
        assertEquals(1.0f, MoonlightTelemetry.touchNorm(Short.MAX_VALUE), 0.0001f)
        assertEquals(0.5f, MoonlightTelemetry.touchNorm(0), 0.0001f)
    }

    @Test
    fun `battery status maps satellite bytes onto Wolf BATTERY_STATE values`() {
        assertEquals(MoonlightControlProtocol.BATTERY_STATE_UNKNOWN, MoonlightTelemetry.batteryState(0))
        assertEquals(MoonlightControlProtocol.BATTERY_DISCHARGING, MoonlightTelemetry.batteryState(1))
        assertEquals(MoonlightControlProtocol.BATTERY_CHARGING, MoonlightTelemetry.batteryState(2))
        assertEquals(MoonlightControlProtocol.BATTERY_FULL, MoonlightTelemetry.batteryState(3))
        assertEquals(MoonlightControlProtocol.BATTERY_NOT_PRESENT, MoonlightTelemetry.batteryState(4))
        assertEquals(MoonlightControlProtocol.BATTERY_STATE_UNKNOWN, MoonlightTelemetry.batteryState(99))
    }

    @Test
    fun `battery percentage passes 0 to 100 and turns everything else unknown`() {
        assertEquals(0, MoonlightTelemetry.batteryPercentage(0))
        assertEquals(100, MoonlightTelemetry.batteryPercentage(100))
        assertEquals(MoonlightControlProtocol.BATTERY_PERCENTAGE_UNKNOWN, MoonlightTelemetry.batteryPercentage(0xFF))
        assertEquals(MoonlightControlProtocol.BATTERY_PERCENTAGE_UNKNOWN, MoonlightTelemetry.batteryPercentage(101))
        assertEquals(MoonlightControlProtocol.BATTERY_PERCENTAGE_UNKNOWN, MoonlightTelemetry.batteryPercentage(-1))
    }
}

class MoonlightMotionGateTest {
    private val gyro = MoonlightControlProtocol.MOTION_TYPE_GYRO
    private val accel = MoonlightControlProtocol.MOTION_TYPE_ACCEL

    @Test
    fun `nothing is wanted before the host asks`() {
        val gate = MoonlightMotionGate()
        assertFalse(gate.wanted(0))
        assertFalse(gate.shouldSend(0, gyro, 0L))
    }

    @Test
    fun `a request opens exactly that controller and type`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(1, 100, gyro)
        assertTrue(gate.wanted(1))
        assertTrue(gate.wanted(1, gyro))
        assertFalse(gate.wanted(1, accel))
        assertFalse(gate.wanted(0))
        assertTrue(gate.shouldSend(1, gyro, 0L))
        assertFalse(gate.shouldSend(1, accel, 0L))
        assertFalse(gate.shouldSend(0, gyro, 0L))
    }

    @Test
    fun `rate zero stops the stream`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(2, 100, accel)
        assertTrue(gate.wanted(2))
        gate.onMotionRequest(2, 0, accel)
        assertFalse(gate.wanted(2))
        assertFalse(gate.shouldSend(2, accel, 0L))
    }

    @Test
    fun `samples pace to the requested rate`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro) // 10 ms interval
        assertTrue(gate.shouldSend(0, gyro, 0L))
        assertFalse(gate.shouldSend(0, gyro, 5_000_000L)) // 5 ms: too soon
        assertTrue(gate.shouldSend(0, gyro, 10_000_000L)) // 10 ms: due
        assertFalse(gate.shouldSend(0, gyro, 15_000_000L))
        assertTrue(gate.shouldSend(0, gyro, 20_000_000L))
    }

    @Test
    fun `types pace independently`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        gate.onMotionRequest(0, 100, accel)
        assertTrue(gate.shouldSend(0, gyro, 0L))
        assertTrue(gate.shouldSend(0, accel, 0L))
        assertFalse(gate.shouldSend(0, gyro, 1_000_000L))
        assertFalse(gate.shouldSend(0, accel, 1_000_000L))
    }

    @Test
    fun `clear drops one controller without touching its siblings`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        gate.onMotionRequest(1, 100, gyro)
        gate.clear(0)
        assertFalse(gate.wanted(0))
        assertTrue(gate.wanted(1))
        gate.clearAll()
        assertFalse(gate.wanted(1))
    }
}

class MoonlightTouchDifferTest {
    private val down = MoonlightControlProtocol.TOUCH_EVENT_DOWN
    private val up = MoonlightControlProtocol.TOUCH_EVENT_UP
    private val move = MoonlightControlProtocol.TOUCH_EVENT_MOVE

    private fun MoonlightTouchDiffer.frame(
        f0: Triple<Int, Float, Float>? = null,
        f1: Triple<Int, Float, Float>? = null,
    ) = diff(
        finger0Active = f0 != null,
        finger0Id = f0?.first ?: 0,
        finger0X = f0?.second ?: 0f,
        finger0Y = f0?.third ?: 0f,
        finger1Active = f1 != null,
        finger1Id = f1?.first ?: 0,
        finger1X = f1?.second ?: 0f,
        finger1Y = f1?.third ?: 0f,
    )

    @Test
    fun `contact lifecycle produces down move up`() {
        val differ = MoonlightTouchDiffer()
        var events = differ.frame(f0 = Triple(3, 0.1f, 0.2f))
        assertEquals(1, events.size)
        assertEquals(down, events[0].eventType)
        assertEquals(3, events[0].pointerId)
        assertEquals(0.1f, events[0].x, 0f)
        assertEquals(1.0f, events[0].pressure, 0f)

        events = differ.frame(f0 = Triple(3, 0.15f, 0.2f))
        assertEquals(1, events.size)
        assertEquals(move, events[0].eventType)
        assertEquals(0.15f, events[0].x, 0f)

        // Identical frame: nothing to say.
        events = differ.frame(f0 = Triple(3, 0.15f, 0.2f))
        assertEquals(0, events.size)

        events = differ.frame()
        assertEquals(1, events.size)
        assertEquals(up, events[0].eventType)
        assertEquals(3, events[0].pointerId)
        assertEquals(0.0f, events[0].pressure, 0f)
    }

    @Test
    fun `two fingers report independently in one frame`() {
        val differ = MoonlightTouchDiffer()
        val events = differ.frame(f0 = Triple(1, 0.1f, 0.1f), f1 = Triple(2, 0.9f, 0.9f))
        assertEquals(2, events.size)
        assertEquals(down, events[0].eventType)
        assertEquals(1, events[0].pointerId)
        assertEquals(down, events[1].eventType)
        assertEquals(2, events[1].pointerId)
    }

    @Test
    fun `a tracking id change is a lift plus a fresh contact`() {
        val differ = MoonlightTouchDiffer()
        differ.frame(f0 = Triple(5, 0.5f, 0.5f))
        val events = differ.frame(f0 = Triple(6, 0.6f, 0.6f))
        assertEquals(2, events.size)
        assertEquals(up, events[0].eventType)
        assertEquals(5, events[0].pointerId)
        assertEquals(down, events[1].eventType)
        assertEquals(6, events[1].pointerId)
    }

    @Test
    fun `reset forgets held contacts so a rebind starts clean`() {
        val differ = MoonlightTouchDiffer()
        differ.frame(f0 = Triple(1, 0.5f, 0.5f))
        differ.reset()
        // No phantom UP for the forgotten finger; the next contact is a fresh DOWN.
        val events = differ.frame(f0 = Triple(1, 0.5f, 0.5f))
        assertEquals(1, events.size)
        assertEquals(down, events[0].eventType)
    }
}
