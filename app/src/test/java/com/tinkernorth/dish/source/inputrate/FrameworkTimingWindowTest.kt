// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameworkTimingWindowTest {
    @Test
    fun `the first event seeds the clock and the next ones measure gap and delay`() {
        val window = FrameworkTimingWindow()
        window.record(eventTimeMs = 1000, nowMs = 1003)
        assertNull(window.summary())

        window.record(eventTimeMs = 1008, nowMs = 1010)
        window.record(eventTimeMs = 1016, nowMs = 1020)
        window.record(eventTimeMs = 1024, nowMs = 1027)
        val summary = window.summary() ?: error("no summary")
        assertEquals(3, summary.samples)
        assertEquals(8f, summary.gapP50Ms, 0f)
        assertEquals(8f, summary.gapP99Ms, 0f)
        assertEquals(3f, summary.delayP50Ms, 0f)
        assertEquals(4f, summary.delayP99Ms, 0f)
    }

    @Test
    fun `a pause longer than the jitter cap is skipped, not recorded as jitter`() {
        val window = FrameworkTimingWindow()
        window.record(eventTimeMs = 0, nowMs = 0)
        window.record(eventTimeMs = 5000, nowMs = 5001)
        assertNull(window.summary())
        window.record(eventTimeMs = 5010, nowMs = 5011)
        assertEquals(10f, window.summary()?.gapP50Ms)
    }

    @Test
    fun `the window slides once it is full`() {
        val window = FrameworkTimingWindow(capacity = 2)
        window.record(0, 0)
        window.record(10, 10)
        window.record(20, 20)
        window.record(60, 60)
        val summary = window.summary() ?: error("no summary")
        assertEquals(2, summary.samples)
        assertEquals(40f, summary.gapP99Ms, 0f)
    }

    @Test
    fun `percentiles use nearest rank`() {
        val values = floatArrayOf(5f, 1f, 3f, 2f, 4f)
        assertEquals(3f, Percentiles.of(values, 0.5), 0f)
        assertEquals(5f, Percentiles.of(values, 0.99), 0f)
        assertEquals(0f, Percentiles.of(FloatArray(0), 0.5), 0f)
    }

    @Test
    fun `the store only records while a screen has it armed`() {
        val store = FrameworkInputTimingStore()
        store.record(1, 0, 0)
        store.record(1, 8, 9)
        assertNull(store.summary(1))
        store.arm()
        store.record(1, 0, 0)
        store.record(1, 8, 9)
        assertEquals(1, store.summary(1)?.samples)
        store.disarm()
        store.record(1, 16, 17)
        assertEquals(1, store.summary(1)?.samples)
    }
}
