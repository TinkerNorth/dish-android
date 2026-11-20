// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

// A host's snapshot is held against one deadline: the connect and the read draw from the same
// budget, so a report never waits the connect timeout and the read timeout one after the other.
class RequestDeadlineTest {
    private var now = 0L
    private val clock = { now }

    @Test
    fun `the read gets only what the connect left of the budget`() {
        val deadline = RequestDeadline(2_000, clock)
        assertEquals(2_000, deadline.remainingMs())
        now = 1_500
        assertEquals(500, deadline.remainingMs())
    }

    @Test
    fun `a spent budget is expired and never reads as zero or negative time`() {
        val deadline = RequestDeadline(2_000, clock)
        now = 2_000
        assertTrue(deadline.expired())
        assertEquals(1, deadline.remainingMs())
        now = 9_000
        assertEquals(1, deadline.remainingMs())
    }

    @Test
    fun `a fresh budget has not expired`() {
        assertFalse(RequestDeadline(2_000, clock).expired())
    }

    @Test
    fun `a host that accepts and then stalls is given up on within one deadline`() {
        ServerSocket(0).use { server ->
            val client = SatelliteHttpClient(SatellitePinRepository(mapBackedPrefs().first))
            val started = System.nanoTime()
            val reply = client.getConnectionDiagnostics("127.0.0.1", server.localPort, "conn", "device", "proof", "satellite:stall", 600)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(reply.timedOut)
            assertEquals(0, reply.status)
            assertTrue("took ${elapsedMs}ms for a 600ms deadline", elapsedMs < 1_100)
        }
    }
}
