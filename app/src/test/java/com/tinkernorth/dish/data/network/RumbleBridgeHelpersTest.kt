// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the pure helpers that shape an incoming `MSG_RUMBLE` packet
 * before the [RumbleBridge] hands it off to Android's vibrator services.
 *
 * The dispatch path itself touches [android.os.VibratorManager] /
 * [android.os.Vibrator], which only exist on a real Android device, so this
 * file deliberately stops short of the system-service boundary. The two
 * transformations covered here are what actually shape the user's wrist
 * experience — the rest is plumbing.
 */
class RumbleBridgeHelpersTest {
    // ── rumbleMagnitudeTo255 ──────────────────────────────────────────────

    @Test
    fun `magnitude 0 stays 0`() {
        assertEquals(0, rumbleMagnitudeTo255(0))
    }

    @Test
    fun `magnitude 65535 maps to 255`() {
        assertEquals(255, rumbleMagnitudeTo255(65535))
    }

    @Test
    fun `magnitude midpoint maps to ~128`() {
        // The exact midpoint of u16 is 32767.5, so 32767 sits just below
        // and rounds down to 127: (32767*255 + 32767)/65535 = 8388352/65535 = 127.
        assertEquals(127, rumbleMagnitudeTo255(32767))
        // 32768 is just above the midpoint and rounds up to 128:
        // (32768*255 + 32767)/65535 = 8388607/65535 = 128.
        assertEquals(128, rumbleMagnitudeTo255(32768))
    }

    @Test
    fun `tiny magnitude rounds up to 1, not down to 0`() {
        // The whole point of the rounding-up clamp: any non-zero wire-format
        // magnitude must produce *some* perceptible vibration so on/off has
        // the same feel as a physical pad.
        assertEquals(1, rumbleMagnitudeTo255(1))
        assertEquals(1, rumbleMagnitudeTo255(50))
        assertEquals(1, rumbleMagnitudeTo255(127))
        assertEquals(1, rumbleMagnitudeTo255(128)) // 128*255+32767 = 65407, /65535 = 0.99 → coerced to 1
    }

    @Test
    fun `magnitude over 65535 is clamped to 255`() {
        // The wire format is u16 so this never legitimately happens, but
        // RumbleBridge sees jints from JNI and a misbehaving satellite could
        // theoretically send anything. Defensive.
        assertEquals(255, rumbleMagnitudeTo255(70000))
        assertEquals(255, rumbleMagnitudeTo255(Int.MAX_VALUE))
    }

    @Test
    fun `magnitude below 0 is treated as 0`() {
        assertEquals(0, rumbleMagnitudeTo255(-1))
        assertEquals(0, rumbleMagnitudeTo255(Int.MIN_VALUE))
    }

    @Test
    fun `magnitude scaling is monotonic`() {
        // Property: more wire-format magnitude must never produce less
        // amplitude. Sanity check at a handful of points covering the curve.
        val points = listOf(0, 100, 1000, 10000, 32768, 50000, 65535)
        var prev = -1
        for (m in points) {
            val v = rumbleMagnitudeTo255(m)
            assert(v >= prev) { "non-monotonic at magnitude=$m: prev=$prev got=$v" }
            prev = v
        }
    }

    // ── rumbleSafeDurationMs ──────────────────────────────────────────────

    @Test
    fun `duration 0 is preserved as the stop sentinel`() {
        // 0 is the "no-op / cancel" marker for the dispatch path. It must
        // round-trip unchanged so the stop branch still triggers.
        assertEquals(0, rumbleSafeDurationMs(0))
    }

    @Test
    fun `duration is clamped into the 1 to 1500 range`() {
        assertEquals(1, rumbleSafeDurationMs(1))
        assertEquals(500, rumbleSafeDurationMs(500))
        assertEquals(1500, rumbleSafeDurationMs(1500))
    }

    @Test
    fun `duration above 1500 is clamped to 1500`() {
        // The cap protects the user from a hung satellite that strands a
        // multi-second buzz on the device.
        assertEquals(1500, rumbleSafeDurationMs(2000))
        assertEquals(1500, rumbleSafeDurationMs(65535))
        assertEquals(1500, rumbleSafeDurationMs(Int.MAX_VALUE))
    }

    @Test
    fun `negative duration is clamped to 1`() {
        // The wire format is u16 so this can't happen in practice, but JNI
        // hands us jints; defensive against a misbehaving satellite.
        assertEquals(1, rumbleSafeDurationMs(-1))
    }
}
