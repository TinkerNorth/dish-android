// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownPadIdsTest {
    private val devices = mapOf(-1000 to "usb", 3 to "pad", 7 to "pad", 130 to "pad", 70000 to "pad")

    @Test
    fun `the snapshot answers every id as the map does`() {
        val ids = knownPadIdsOf(devices)
        val probes = (-2000..300) + listOf(Int.MIN_VALUE, -1, 0, 69999, 70000, 70001, Int.MAX_VALUE)
        for (id in probes) {
            assertEquals("id $id", devices.containsKey(id), id in ids)
        }
    }

    @Test
    fun `an empty registry knows no pad`() {
        val ids = knownPadIdsOf(emptyMap<Int, String>())
        assertFalse(0 in ids)
        assertFalse(-1000 in ids)
    }

    @Test
    fun `a snapshot is taken from its own map and no equal copy of it`() {
        val ids = knownPadIdsOf(devices)
        assertTrue(ids.isTakenFrom(devices))
        assertFalse(ids.isTakenFrom(devices.toMap()))
    }
}
