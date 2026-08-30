// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The reference count itself: one session per host, up to four pads, each binding
// holding one controller number for as long as it points at the host.
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightConnectionPadsTest {
    private val dispatcher = StandardTestDispatcher()
    private val host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc")

    private fun connection() = MoonlightConnection(host.id, host, TestScope(dispatcher), dispatcher)

    private fun MoonlightConnection.take(slotId: String) =
        acquirePad(
            slotId = slotId,
            emulatedType = MoonlightEmulatedType.XBOX,
            capabilities = 0x03,
            supportedButtons = 0xFFFF,
        )

    @Test
    fun `pads take the lowest free controller number in order`() {
        val conn = connection()
        assertEquals(0, conn.take("a")?.number)
        assertEquals(1, conn.take("b")?.number)
        assertEquals(2, conn.take("c")?.number)
        assertEquals(3, conn.take("d")?.number)
        assertEquals(4, conn.padCount)
    }

    @Test
    fun `a fifth pad is refused because a session carries four`() {
        val conn = connection()
        listOf("a", "b", "c", "d").forEach { assertNotNull(conn.take(it)) }
        assertFalse(conn.hasRoom)
        assertNull(conn.take("e"))
        assertEquals(4, conn.padCount)
        assertEquals(MoonlightConnection.MAX_PADS, conn.padCount)
    }

    @Test
    fun `a slot that already holds a pad keeps its number instead of taking a second`() {
        val conn = connection()
        val first = conn.take("a")
        assertEquals(first, conn.take("a"))
        assertEquals(1, conn.padCount)
    }

    @Test
    fun `a released number is handed to the next pad, and only then`() {
        val conn = connection()
        conn.take("a")
        conn.take("b")
        conn.take("c")
        assertEquals(2, conn.releasePad("b"))
        assertNull(conn.padFor("b"))
        assertEquals(1, conn.take("d")?.number)
    }

    @Test
    fun `releasing a slot that holds nothing changes nothing`() {
        val conn = connection()
        conn.take("a")
        assertEquals(1, conn.releasePad("nobody"))
        assertEquals(1, conn.padCount)
    }

    @Test
    fun `the active mask carries every bound pad and clears the one that left`() {
        val conn = connection()
        conn.take("a")
        conn.take("b")
        conn.take("c")
        assertEquals(0b0111, conn.activeMask())
        conn.releasePad("b")
        assertEquals(0b0101, conn.activeMask())
        conn.releasePad("a")
        conn.releasePad("c")
        assertEquals(0, conn.activeMask())
    }

    @Test
    fun `a pad carries the type and bits its own binding asked for`() {
        val conn = connection()
        conn.take("a")
        val ps =
            conn.acquirePad(
                slotId = "b",
                emulatedType = MoonlightEmulatedType.PLAYSTATION,
                capabilities = 0xBF,
                supportedButtons = 0xFFFF or 0x100000,
            )
        assertEquals(MoonlightEmulatedType.XBOX, conn.padFor("a")?.emulatedType)
        assertEquals(MoonlightEmulatedType.PLAYSTATION, ps?.emulatedType)
        assertEquals(0xBF, ps?.capabilities)
        assertEquals(0x03, conn.padFor("a")?.capabilities)
    }

    @Test
    fun `a drop and a host-ended session are distinguishable from a clean idle`() {
        val conn = connection()
        assertEquals(MoonlightSessionState.Idle, conn.state.value)
        conn.markLaunching()
        assertEquals(MoonlightSessionState.Launching, conn.state.value)
        conn.markDropped()
        assertEquals(MoonlightSessionState.Dropped, conn.state.value)
        conn.markEnded()
        assertEquals(MoonlightSessionState.Ended, conn.state.value)
        conn.markDisconnected()
        assertEquals(MoonlightSessionState.Idle, conn.state.value)
    }

    @Test
    fun `tearing the session down leaves the pads their bindings still claim`() {
        val conn = connection()
        conn.take("a")
        conn.take("b")
        conn.markDropped()
        assertEquals(2, conn.padCount)
        assertTrue(conn.hasRoom)
    }
}
