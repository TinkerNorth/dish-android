// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WriteVerdictTest {
    private fun fields(
        recorded: Long = 1,
        route: Long = 1,
        endpoint: Long = 3,
        reportId: Long = 2,
        ok: Long = 1,
        err: Long = 0,
        ageMs: Long = 12,
    ) = longArrayOf(recorded, route, endpoint, reportId, ok, err, ageMs)

    @Test
    fun `a recorded interrupt out write reads back with its endpoint, report id and age`() {
        assertEquals(WriteVerdict(WriteRoute.INTERRUPT_OUT, 0x03, 0x02, ok = true, err = 0, ageMs = 12), writeVerdictOf(fields()))
    }

    @Test
    fun `a set report write and a pad with no route read as their routes`() {
        assertEquals(WriteRoute.SET_REPORT, writeVerdictOf(fields(route = 2))?.route)
        assertEquals(WriteRoute.NONE, writeVerdictOf(fields(route = 0, ok = 0))?.route)
    }

    @Test
    fun `nothing recorded is no verdict`() {
        assertNull(writeVerdictOf(fields(recorded = 0)))
    }

    @Test
    fun `a missing or short array is no verdict`() {
        assertNull(writeVerdictOf(null))
        assertNull(writeVerdictOf(longArrayOf(1, 1)))
    }

    @Test
    fun `an unknown route is no verdict`() {
        assertNull(writeVerdictOf(fields(route = 9)))
    }

    @Test
    fun `an errno reads by its name and an unknown one by its number`() {
        assertEquals("EPIPE", errName(32))
        assertEquals("ETIMEDOUT", errName(110))
        assertEquals("ENODEV", errName(19))
        assertEquals("short write", errName(-1))
        assertEquals("errno 200", errName(200))
    }

    @Test
    fun `the report id is two hex digits`() {
        assertEquals("02", reportIdHex(2))
        assertEquals("a5", reportIdHex(0xA5))
    }

    @Test
    fun `routes have their bundle names`() {
        assertEquals(listOf("none", "interruptOut", "setReport"), WriteRoute.entries.map { it.wire })
    }
}
