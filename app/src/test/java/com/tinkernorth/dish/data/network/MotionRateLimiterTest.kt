// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.network.MotionRateLimiter.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MotionRateLimiter] — the per-controller 250 Hz gate the
 * roadmap (Tier 1 Task 1.1 acceptance criterion) requires before
 * [SatelliteNative.sendMotion].
 *
 * All tests inject a fake monotonic clock via the constructor so the gate
 * boundaries are deterministic. The gate is measured from the last emitted
 * packet — never the last attempt — so a dropped sample MUST NOT advance the
 * gate. That regression behaviour gets its own test.
 */
class MotionRateLimiterTest {
    private val emitted = mutableListOf<Pair<MotionSample, Int>>()
    private val emit = MotionRateLimiter.Emit { s, dt -> emitted += s to dt }

    private fun limiter(nowUs: () -> Long) = MotionRateLimiter(nowUs)

    private val anySample = MotionSample(1, 2, 3, 4, 5, 6)

    @Test
    fun `first sample emits with delta zero`() {
        var now = 1_000_000L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        assertEquals(1, emitted.size)
        assertEquals(0, emitted[0].second)
    }

    @Test
    fun `sample inside 4 ms gate drops`() {
        var now = 1_000_000L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        now = 1_000_000L + 3_999
        assertFalse(rl.publish(0, anySample, emit))
        assertEquals(1, emitted.size)
    }

    @Test
    fun `sample at exactly 4 ms boundary forwards`() {
        var now = 0L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        now = MotionRateLimiter.MIN_INTERVAL_US
        assertTrue(rl.publish(0, anySample, emit))
        assertEquals(2, emitted.size)
        assertEquals(MotionRateLimiter.MIN_INTERVAL_US.toInt(), emitted[1].second)
    }

    @Test
    fun `dropped attempts do not advance the gate`() {
        var now = 0L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        now = 1_000
        assertFalse(rl.publish(0, anySample, emit))
        now = 2_000
        assertFalse(rl.publish(0, anySample, emit))
        now = 3_000
        assertFalse(rl.publish(0, anySample, emit))
        // At 4 000 µs the gate (measured from last EMITTED, not last attempt)
        // opens. If dropped attempts had advanced the gate this would have been
        // pushed out to 4 000 + 3 000 = 7 000 µs.
        now = 4_000
        assertTrue(rl.publish(0, anySample, emit))
        assertEquals(2, emitted.size)
    }

    @Test
    fun `gate is per-controller`() {
        var now = 0L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        assertTrue(rl.publish(1, anySample, emit))
        now = 1_000
        assertFalse(rl.publish(0, anySample, emit))
        assertFalse(rl.publish(1, anySample, emit))
        assertEquals(2, emitted.size)
    }

    @Test
    fun `clear resets the per-controller gate`() {
        var now = 0L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        rl.clear(0)
        now = 1
        // After clear, the same controller behaves as first-sample: delta 0,
        // forwarded.
        assertTrue(rl.publish(0, anySample, emit))
        assertEquals(2, emitted.size)
        assertEquals(0, emitted[1].second)
    }

    @Test
    fun `sample data passes through verbatim`() {
        var now = 0L
        val rl = limiter { now }

        val s = MotionSample(100, -200, 300, -400, 500, -600)
        rl.publish(0, s, emit)
        assertEquals(s, emitted[0].first)
    }

    @Test
    fun `documented default rate is 250 Hz with 4000 us interval`() {
        // Pin the public constants to the values the satellite protocol doc
        // promises. Bumping these would silently break the wire-rate
        // acceptance criterion.
        assertEquals(250, MotionRateLimiter.RATE_LIMIT_HZ)
        assertEquals(4_000L, MotionRateLimiter.MIN_INTERVAL_US)
    }

    @Test
    fun `delta clamps at uint32 max for absurd gaps`() {
        // 71+ minute gap between samples (more than uint32 µs can represent).
        // The processor clamps to 0xFFFFFFFF — the receiver's documented
        // saturation value — rather than wrapping.
        var now = 0L
        val rl = limiter { now }

        assertTrue(rl.publish(0, anySample, emit))
        now = 0x1_0000_0000L // 2^32 µs, just past uint32_max
        assertTrue(rl.publish(0, anySample, emit))
        assertEquals(-1, emitted[1].second) // 0xFFFFFFFF reinterpreted as Int
    }

    @Test
    fun `clearAll wipes every controller`() {
        var now = 0L
        val rl = limiter { now }

        rl.publish(0, anySample, emit)
        rl.publish(1, anySample, emit)
        rl.clearAll()
        now = 1
        // Both controllers behave as first-sample again.
        assertTrue(rl.publish(0, anySample, emit))
        assertTrue(rl.publish(1, anySample, emit))
        assertEquals(0, emitted[2].second)
        assertEquals(0, emitted[3].second)
    }
}
