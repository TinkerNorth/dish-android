// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// The keyed-map reducers the stores hand to setState. A store's StateFlow drops a map equal to the
// one it holds, so whether a no-op builds a new map at all is only visible here, by reference.
class StoreReducersTest {
    private val held = mapOf(FIRST_KEY to FIRST_VALUE)

    @Test
    fun `an absent key is added`() {
        assertEquals(mapOf(FIRST_KEY to FIRST_VALUE, SECOND_KEY to SECOND_VALUE), withEntryIfAbsent(held, SECOND_KEY, SECOND_VALUE))
    }

    @Test
    fun `a present key keeps its value and hands back the same map`() {
        assertSame(held, withEntryIfAbsent(held, FIRST_KEY, SECOND_VALUE))
    }

    @Test
    fun `a present key is removed and the others stay`() {
        val both = held + (SECOND_KEY to SECOND_VALUE)

        assertEquals(mapOf(SECOND_KEY to SECOND_VALUE), withoutEntry(both, FIRST_KEY))
    }

    @Test
    fun `removing an absent key hands back the same map`() {
        assertSame(held, withoutEntry(held, SECOND_KEY))
    }

    private companion object {
        val FIRST_KEY = "conn-1" to "slot-A"
        val SECOND_KEY = "conn-1" to "slot-B"
        const val FIRST_VALUE = 1
        const val SECOND_VALUE = 2
    }
}
