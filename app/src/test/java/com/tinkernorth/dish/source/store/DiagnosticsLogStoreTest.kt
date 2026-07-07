// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsLogStoreTest {
    @Test
    fun `entries append in order with their timestamps`() {
        val store = DiagnosticsLogStore()
        store.log("link", "first", atMs = 100)
        store.log("pad", "second", atMs = 200)

        assertEquals(
            listOf(
                DiagnosticsLogEntry(100, "link", "first"),
                DiagnosticsLogEntry(200, "pad", "second"),
            ),
            store.state.value,
        )
    }

    @Test
    fun `the ring drops the oldest entries past the cap`() {
        val store = DiagnosticsLogStore()
        repeat(DiagnosticsLogStore.MAX_ENTRIES + 5) { store.log("t", "m$it", atMs = it.toLong()) }

        val entries = store.state.value
        assertEquals(DiagnosticsLogStore.MAX_ENTRIES, entries.size)
        assertEquals("m5", entries.first().message)
        assertEquals("m${DiagnosticsLogStore.MAX_ENTRIES + 4}", entries.last().message)
    }
}
