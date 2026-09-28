// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickTestHistoryStoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stickHistoryKeyFor uses the vendor and product ids`() {
        assertEquals("1118:654", stickHistoryKeyFor(1118, 654, "Pad"))
    }

    @Test
    fun `stickHistoryKeyFor falls back to the name when the model has no ids`() {
        assertEquals("Pad", stickHistoryKeyFor(0, 654, "Pad"))
        assertEquals("Pad", stickHistoryKeyFor(1118, 0, "Pad"))
    }

    @Test
    fun `an empty history reads as empty`() {
        val (ctx, _) = mapBackedPrefs()

        assertTrue(StickTestHistoryStore(ctx, json).state.value.isEmpty())
    }

    @Test
    fun `a corrupt history blob reads as empty`() {
        val (ctx, _) = mapBackedPrefs(mutableMapOf(PERSISTED_KEY to "{not json"))

        assertTrue(StickTestHistoryStore(ctx, json).state.value.isEmpty())
    }

    @Test
    fun `noteDrift records the drift half of the record`() {
        val (ctx, _) = mapBackedPrefs()
        val store = StickTestHistoryStore(ctx, json)

        store.noteDrift("k", left = 0.1f, right = 0.2f, suggestedDeadzone = 0.15f, nowMs = 5L)

        assertEquals(
            StickTestRecord(driftAtMs = 5L, driftLeft = 0.1f, driftRight = 0.2f, suggestedDeadzone = 0.15f),
            store.recordFor("k"),
        )
    }

    @Test
    fun `noteRange keeps an earlier drift result`() {
        val (ctx, _) = mapBackedPrefs()
        val store = StickTestHistoryStore(ctx, json)
        store.noteDrift("k", left = 0.1f, right = 0.2f, suggestedDeadzone = 0.15f, nowMs = 5L)

        store.noteRange("k", reachLeft = 0.9f, reachRight = 0.95f, circularityLeft = null, circularityRight = 0.8f, nowMs = 9L)

        val record = store.recordFor("k")
        assertEquals(5L, record?.driftAtMs)
        assertEquals(0.15f, record?.suggestedDeadzone)
        assertEquals(9L, record?.rangeAtMs)
        assertEquals(0.9f, record?.reachLeft)
        assertNull(record?.circularityLeft)
        assertEquals(0.8f, record?.circularityRight)
    }

    @Test
    fun `noteDrift keeps an earlier range result`() {
        val (ctx, _) = mapBackedPrefs()
        val store = StickTestHistoryStore(ctx, json)
        store.noteRange("k", reachLeft = 0.9f, reachRight = 0.95f, circularityLeft = 0.7f, circularityRight = 0.8f, nowMs = 9L)

        store.noteDrift("k", left = 0.1f, right = 0.2f, suggestedDeadzone = 0.15f, nowMs = 12L)

        val record = store.recordFor("k")
        assertEquals(9L, record?.rangeAtMs)
        assertEquals(0.95f, record?.reachRight)
        assertEquals(12L, record?.driftAtMs)
    }

    @Test
    fun `a note persists the whole history and a new store reads it back`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = StickTestHistoryStore(ctx, json)
        store.noteDrift("a", left = 0.1f, right = 0.2f, suggestedDeadzone = 0.15f, nowMs = 5L)
        store.noteRange("b", reachLeft = 0.9f, reachRight = 0.95f, circularityLeft = null, circularityRight = null, nowMs = 9L)
        assertTrue(backing.containsKey(PERSISTED_KEY))
        verifyOnlyPrefsFile(ctx, PERSISTED_FILE)

        val reread = StickTestHistoryStore(ctx, json)

        assertEquals(store.state.value, reread.state.value)
        assertEquals(2, reread.state.value.size)
    }

    @Test
    fun `records are kept per key`() {
        val (ctx, _) = mapBackedPrefs()
        val store = StickTestHistoryStore(ctx, json)

        store.noteDrift("a", left = 0.1f, right = 0.2f, suggestedDeadzone = 0.15f, nowMs = 5L)

        assertNull(store.recordFor("b"))
    }

    // Backup schema: the history is its own file, apart from the user preferences.
    private companion object {
        const val PERSISTED_FILE = "stick_test_history"
        const val PERSISTED_KEY = "records"
    }
}
