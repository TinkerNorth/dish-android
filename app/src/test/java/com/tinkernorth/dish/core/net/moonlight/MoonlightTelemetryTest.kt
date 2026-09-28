// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.architecture.testing.fewestAllocatedBytesDuring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightTelemetryTest {
    @Test
    fun `gyro wire scale maps full range to 2000 deg per second`() {
        assertEquals(2000.0f, gyroDegS(32767), 0.001f)
        assertEquals(-2000.0f, gyroDegS(-32767), 0.001f)
        assertEquals(0.0f, gyroDegS(0), 0.0f)
        // 1 deg/s = 32767/2000 wire units.
        assertEquals(1.0f, gyroDegS(16), 0.05f)
    }

    @Test
    fun `accel wire scale maps full range to 4 g in meters per second squared`() {
        assertEquals(4 * 9.80665f, accelMs2(32767), 0.001f)
        assertEquals(-4 * 9.80665f, accelMs2(-32767), 0.001f)
        // 1 g = 8191.75 wire units.
        assertEquals(9.80665f, accelMs2(8192), 0.01f)
    }

    @Test
    fun `touch coordinates normalize the full int16 range onto 0 to 1`() {
        assertEquals(0.0f, touchNorm(Short.MIN_VALUE), 0.0f)
        assertEquals(1.0f, touchNorm(Short.MAX_VALUE), 0.0001f)
        assertEquals(0.5f, touchNorm(0), 0.0001f)
    }

    @Test
    fun `battery status maps satellite bytes onto Wolf BATTERY_STATE values`() {
        assertEquals(BATTERY_STATE_UNKNOWN, batteryState(0))
        assertEquals(BATTERY_DISCHARGING, batteryState(1))
        assertEquals(BATTERY_CHARGING, batteryState(2))
        assertEquals(BATTERY_FULL, batteryState(3))
        assertEquals(BATTERY_NOT_PRESENT, batteryState(4))
        assertEquals(BATTERY_STATE_UNKNOWN, batteryState(99))
    }

    @Test
    fun `battery percentage passes 0 to 100 and turns everything else unknown`() {
        assertEquals(0, batteryPercentage(0))
        assertEquals(100, batteryPercentage(100))
        assertEquals(BATTERY_PERCENTAGE_UNKNOWN, batteryPercentage(0xFF))
        assertEquals(BATTERY_PERCENTAGE_UNKNOWN, batteryPercentage(101))
        assertEquals(BATTERY_PERCENTAGE_UNKNOWN, batteryPercentage(-1))
    }
}

class MoonlightMotionGateTest {
    private val gyro = MOTION_TYPE_GYRO
    private val accel = MOTION_TYPE_ACCEL

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

    @Test
    fun `a stop then start sends the first sample at once`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        assertTrue(gate.shouldSend(0, gyro, 0L))
        gate.onMotionRequest(0, 0, gyro)
        gate.onMotionRequest(0, 100, gyro)
        assertTrue(gate.shouldSend(0, gyro, 1L))
    }

    @Test
    fun `a rate change keeps the pacing clock`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        assertTrue(gate.shouldSend(0, gyro, 0L))
        gate.onMotionRequest(0, 200, gyro)
        assertFalse(gate.shouldSend(0, gyro, 1L))
        assertTrue(gate.shouldSend(0, gyro, 5_000_000L))
    }

    @Test
    fun `a sample one nanosecond short of the interval waits`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        assertTrue(gate.shouldSend(0, gyro, 0L))
        assertFalse(gate.shouldSend(0, gyro, 9_999_999L))
        assertTrue(gate.shouldSend(0, gyro, 10_000_000L))
    }

    @Test
    fun `stopping one type leaves the controller's other type streaming`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        gate.onMotionRequest(0, 100, accel)
        gate.onMotionRequest(0, 0, gyro)
        assertTrue(gate.wanted(0))
        assertFalse(gate.wanted(0, gyro))
        assertTrue(gate.wanted(0, accel))
        assertTrue(gate.shouldSend(0, accel, 0L))
    }

    @Test
    fun `a stop for a stream never started changes nothing`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, accel)
        gate.onMotionRequest(0, 0, gyro)
        gate.onMotionRequest(1, -1, gyro)
        assertTrue(gate.wanted(0, accel))
        assertFalse(gate.wanted(0, gyro))
        assertFalse(gate.wanted(1))
    }

    @Test
    fun `a controller asking only for accel is wanted`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(3, 100, accel)
        assertTrue(gate.wanted(3))
        assertFalse(gate.wanted(2))
    }

    @Test
    fun `clear drops every type of that controller`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        gate.onMotionRequest(0, 100, accel)
        gate.onMotionRequest(1, 100, accel)
        gate.clear(0)
        assertFalse(gate.wanted(0, gyro))
        assertFalse(gate.wanted(0, accel))
        assertTrue(gate.wanted(1, accel))
    }

    @Test
    fun `a cleared stream asked for again sends its first sample at once`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(0, 100, gyro)
        assertTrue(gate.shouldSend(0, gyro, 0L))
        gate.clear(0)
        gate.onMotionRequest(0, 100, gyro)
        assertTrue(gate.shouldSend(0, gyro, 1L))
    }

    // Whatever u16 number and byte type a host sends is kept as sent, as the map it replaced did.
    @Test
    fun `any number and type a host sends is paced on its own`() {
        val gate = MoonlightMotionGate()
        gate.onMotionRequest(LAST_CONTROLLER, 100, UNKNOWN_MOTION_TYPE)
        assertTrue(gate.wanted(LAST_CONTROLLER))
        assertTrue(gate.wanted(LAST_CONTROLLER, UNKNOWN_MOTION_TYPE))
        assertFalse(gate.wanted(LAST_CONTROLLER, gyro))
        assertTrue(gate.shouldSend(LAST_CONTROLLER, UNKNOWN_MOTION_TYPE, 0L))
        assertFalse(gate.shouldSend(LAST_CONTROLLER, UNKNOWN_MOTION_TYPE, 1L))
    }

    private val pacedGate = MoonlightMotionGate()
    private var sentSamples = 0
    private var sampleNs = 0L

    // What a pad's sensor thread asks per sample: whether anything is wanted, then each type in
    // turn, for the last controller a session carries, whose key is past the boxed-integer cache.
    private fun runSampleCycle() {
        repeat(MEASURED_SAMPLES) {
            sampleNs += SAMPLE_INTERVAL_NS
            if (pacedGate.wanted(LAST_CONTROLLER)) sentSamples++
            if (pacedGate.wanted(LAST_CONTROLLER, gyro)) sentSamples++
            if (pacedGate.shouldSend(LAST_CONTROLLER, gyro, sampleNs)) sentSamples++
            if (pacedGate.shouldSend(LAST_CONTROLLER, accel, sampleNs)) sentSamples++
        }
    }

    @Test
    fun `a sample's checks allocate nothing`() {
        pacedGate.onMotionRequest(FIRST_CONTROLLER, SAMPLE_RATE_HZ, gyro)
        pacedGate.onMotionRequest(LAST_CONTROLLER, SAMPLE_RATE_HZ, gyro)
        pacedGate.onMotionRequest(LAST_CONTROLLER, SAMPLE_RATE_HZ, accel)
        runSampleCycle()
        sentSamples = 0
        val allocated = fewestAllocatedBytesDuring(MEASURED_RUNS, ::runSampleCycle)
        assertEquals(MEASURED_RUNS * MEASURED_SAMPLES * CHECKS_PASSED_PER_SAMPLE, sentSamples)
        assertTrue("$allocated bytes over $MEASURED_SAMPLES samples", allocated < MEASURED_SAMPLES * BYTES_PER_SAMPLE_BOUND)
    }

    private companion object {
        const val FIRST_CONTROLLER = 0

        // A u16 on the wire: far past the Integer cache, as any number a host sends may be.
        const val LAST_CONTROLLER = 0xFFFF
        const val UNKNOWN_MOTION_TYPE = 0xFF
        const val SAMPLE_RATE_HZ = 100
        const val SAMPLE_INTERVAL_NS = 10_000_000L
        const val MEASURED_SAMPLES = 1000
        const val MEASURED_RUNS = 3
        const val CHECKS_PASSED_PER_SAMPLE = 4

        // Half the smallest object: a key or an iterator costs 16 bytes or more every sample.
        const val BYTES_PER_SAMPLE_BOUND = 8
    }
}

class MoonlightTouchDifferTest {
    private val down = TOUCH_EVENT_DOWN
    private val up = TOUCH_EVENT_UP
    private val move = TOUCH_EVENT_MOVE

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

    @Test
    fun `the second finger moves and lifts independently of the first`() {
        val differ = MoonlightTouchDiffer()
        differ.frame(f0 = Triple(1, 0.1f, 0.1f), f1 = Triple(2, 0.9f, 0.9f))

        val moved = differ.frame(f0 = Triple(1, 0.1f, 0.1f), f1 = Triple(2, 0.8f, 0.8f))
        assertEquals(1, moved.size)
        assertEquals(move, moved[0].eventType)
        assertEquals(2, moved[0].pointerId)
        assertEquals(0.8f, moved[0].x, 0f)

        val lifted = differ.frame(f0 = Triple(1, 0.1f, 0.1f))
        assertEquals(1, lifted.size)
        assertEquals(up, lifted[0].eventType)
        assertEquals(2, lifted[0].pointerId)
    }

    @Test
    fun `a tracking id change on the second finger is a lift plus a fresh contact`() {
        val differ = MoonlightTouchDiffer()
        differ.frame(f0 = Triple(1, 0.1f, 0.1f), f1 = Triple(2, 0.9f, 0.9f))
        val events = differ.frame(f0 = Triple(1, 0.1f, 0.1f), f1 = Triple(3, 0.7f, 0.7f))
        assertEquals(listOf(up, down), events.map { it.eventType })
        assertEquals(listOf(2, 3), events.map { it.pointerId })
    }

    @Test
    fun `lifting the first finger leaves the second one held`() {
        val differ = MoonlightTouchDiffer()
        differ.frame(f0 = Triple(1, 0.1f, 0.1f), f1 = Triple(2, 0.9f, 0.9f))
        val events = differ.frame(f1 = Triple(2, 0.9f, 0.9f))
        assertEquals(1, events.size)
        assertEquals(up, events[0].eventType)
        assertEquals(1, events[0].pointerId)
    }
}
