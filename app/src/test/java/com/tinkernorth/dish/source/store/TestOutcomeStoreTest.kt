// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestOutcomeStoreTest {
    private val store = TestOutcomeStore()

    @Test
    fun `an outcome is kept for its slot`() {
        store.note("3", BenchTest.RUMBLE_STRONG, worked = true, atMs = 10)
        assertEquals(listOf(TestOutcome("rumble.strong", "worked", 10)), store.outcomesFor("3"))
        assertTrue(store.outcomesFor("4").isEmpty())
    }

    @Test
    fun `outcomes are kept oldest first`() {
        store.note("3", BenchTest.RUMBLE_WEAK, worked = true, atMs = 1)
        store.note("3", BenchTest.TONE, worked = false, atMs = 2)
        assertEquals(listOf("rumble.weak", "tone.play"), store.outcomesFor("3").map { it.test })
    }

    @Test
    fun `the twenty-first outcome drops the oldest`() {
        (1..21).forEach { store.note("3", BenchTest.RUMBLE_BOTH, worked = true, atMs = it.toLong()) }
        val kept = store.outcomesFor("3")
        assertEquals(TEST_OUTCOMES_KEPT, kept.size)
        assertEquals(2L, kept.first().atMs)
        assertEquals(21L, kept.last().atMs)
    }

    @Test
    fun `the last outcome is the newest`() {
        assertNull(store.lastOutcomeFor("3"))
        store.note("3", BenchTest.RUMBLE_WEAK, worked = true, atMs = 1)
        store.note("3", BenchTest.MIC, worked = false, atMs = 2)
        assertEquals(TestOutcome("mic.level", "didNotWork", 2), store.lastOutcomeFor("3"))
    }

    @Test
    fun `a yes is worked and a no is didNotWork`() {
        assertEquals("worked", resultOf(worked = true))
        assertEquals("didNotWork", resultOf(worked = false))
    }

    @Test
    fun `every bench test has its bundle name, in the contract's order`() {
        assertEquals(
            listOf(
                "rumble.weak",
                "rumble.strong",
                "rumble.both",
                "triggerRumble.left",
                "triggerRumble.right",
                "triggerRumble.both",
                "lightbar.cycle",
                "playerLeds.cycle",
                "trigger.pulse",
                "lamp.on",
                "lamp.pulse",
                "tone.play",
                "haptics.play",
                "mic.level",
                "stick.drift",
                "stick.range",
            ),
            BenchTest.entries.map { it.wire },
        )
    }
}
