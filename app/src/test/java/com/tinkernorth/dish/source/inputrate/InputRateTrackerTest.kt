// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import org.junit.Assert.assertEquals
import org.junit.Test

class InputRateTrackerTest {
    @Test
    fun `first update seeds without producing a rate`() {
        val tracker = InputRateTracker()
        tracker.update(count = 500L, nowMs = 1000L)
        assertEquals(0, tracker.lastHz)
        assertEquals(0, tracker.peakHz)
    }

    @Test
    fun `rate derives from the count delta over the window`() {
        val tracker = InputRateTracker()
        tracker.update(count = 0L, nowMs = 1000L)
        tracker.update(count = 500L, nowMs = 1500L)
        assertEquals(1000, tracker.lastHz)
        assertEquals(1000, tracker.peakHz)
    }

    @Test
    fun `window rate quantises to 5 Hz steps`() {
        assertEquals(125, windowRateHz(deltaCount = 62, deltaMs = 500))
        assertEquals(125, windowRateHz(deltaCount = 63, deltaMs = 500))
        assertEquals(250, windowRateHz(deltaCount = 124, deltaMs = 496))
        assertEquals(0, windowRateHz(deltaCount = 1, deltaMs = 500))
        assertEquals(0, windowRateHz(deltaCount = 0, deltaMs = 500))
        assertEquals(0, windowRateHz(deltaCount = -10, deltaMs = 500))
        assertEquals(0, windowRateHz(deltaCount = 10, deltaMs = 0))
    }

    @Test
    fun `last measurement holds through an idle window`() {
        val tracker = InputRateTracker()
        tracker.update(count = 0L, nowMs = 1000L)
        tracker.update(count = 60L, nowMs = 1500L)
        assertEquals(120, tracker.lastHz)
        tracker.update(count = 60L, nowMs = 2000L)
        assertEquals(120, tracker.lastHz)
        assertEquals(120, tracker.peakHz)
    }

    @Test
    fun `a faster window raises both last and peak`() {
        val tracker = InputRateTracker()
        tracker.update(count = 0L, nowMs = 1000L)
        tracker.update(count = 30L, nowMs = 1500L)
        assertEquals(60, tracker.lastHz)
        tracker.update(count = 90L, nowMs = 2000L)
        assertEquals(120, tracker.lastHz)
        assertEquals(120, tracker.peakHz)
        tracker.update(count = 120L, nowMs = 2500L)
        assertEquals(60, tracker.lastHz)
        assertEquals(120, tracker.peakHz)
    }

    @Test
    fun `rebaseline drops one window and keeps the held values`() {
        val tracker = InputRateTracker()
        tracker.update(count = 0L, nowMs = 1000L)
        tracker.update(count = 60L, nowMs = 1500L)
        tracker.rebaseline()
        assertEquals(120, tracker.lastHz)
        assertEquals(120, tracker.peakHz)
        tracker.update(count = 200L, nowMs = 5000L)
        assertEquals(120, tracker.lastHz)
        tracker.update(count = 260L, nowMs = 5500L)
        assertEquals(120, tracker.lastHz)
        assertEquals(120, tracker.peakHz)
    }

    @Test
    fun `a counter reset cannot produce a negative rate`() {
        val tracker = InputRateTracker()
        tracker.update(count = 1000L, nowMs = 1000L)
        tracker.update(count = 5L, nowMs = 1500L)
        assertEquals(0, tracker.lastHz)
        assertEquals(0, tracker.peakHz)
    }
}
