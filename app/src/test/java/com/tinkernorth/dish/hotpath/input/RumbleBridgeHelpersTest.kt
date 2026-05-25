// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import org.junit.Assert.assertEquals
import org.junit.Test

class RumbleBridgeHelpersTest {
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
        assertEquals(127, rumbleMagnitudeTo255(32767))
        assertEquals(128, rumbleMagnitudeTo255(32768))
    }

    @Test
    fun `tiny magnitude rounds up to 1, not down to 0`() {
        // Non-zero magnitude must produce perceptible vibration so on/off matches a physical pad.
        assertEquals(1, rumbleMagnitudeTo255(1))
        assertEquals(1, rumbleMagnitudeTo255(50))
        assertEquals(1, rumbleMagnitudeTo255(127))
        assertEquals(1, rumbleMagnitudeTo255(128))
    }

    @Test
    fun `magnitude over 65535 is clamped to 255`() {
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
        val points = listOf(0, 100, 1000, 10000, 32768, 50000, 65535)
        var prev = -1
        for (m in points) {
            val v = rumbleMagnitudeTo255(m)
            assert(v >= prev) { "non-monotonic at magnitude=$m: prev=$prev got=$v" }
            prev = v
        }
    }

    @Test
    fun `duration 0 is preserved as the stop sentinel`() {
        // 0 is the cancel sentinel; must round-trip unchanged so the stop branch triggers.
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
        assertEquals(1500, rumbleSafeDurationMs(2000))
        assertEquals(1500, rumbleSafeDurationMs(65535))
        assertEquals(1500, rumbleSafeDurationMs(Int.MAX_VALUE))
    }

    @Test
    fun `negative duration is clamped to 1`() {
        assertEquals(1, rumbleSafeDurationMs(-1))
    }
}
