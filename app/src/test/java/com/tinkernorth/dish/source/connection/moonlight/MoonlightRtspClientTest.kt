// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drives [MoonlightRtspClient] against a loopback host that behaves the way a
 * real Moonlight host does: it answers exactly ONE message per TCP connection
 * and then hangs up, and it frames the DESCRIBE body by that hang-up rather than
 * with a Content-length.
 *
 * That is the whole point of the fixture. A client that keeps the socket for a
 * second message gets end-of-stream instead of a reply, which is exactly what
 * broke stream setup against a live Sunshine host: it answered OPTIONS, closed,
 * and never saw the DESCRIBE we wrote into the dead socket.
 */
class MoonlightRtspClientTest {
    private lateinit var host: RtspHost

    @After
    fun tearDown() {
        if (::host.isInitialized) host.close()
    }

    @Test
    fun `completes the handshake, one connection per message`() {
        host = RtspHost()

        val ports = MoonlightRtspClient("127.0.0.1", host.port).handshake(1280, 720, 30)

        assertEquals(MoonlightRtspClient.StreamPorts(47999, 47998, 48000, 4270471497L.toInt(), RtspHost.PING), ports)
        // Seven messages, seven connections, and the host read one request on
        // each. Reusing a connection would have stalled at the second message.
        assertEquals(
            listOf("OPTIONS", "DESCRIBE", "SETUP", "SETUP", "SETUP", "ANNOUNCE", "PLAY"),
            host.awaitCommands(EXPECTED_MESSAGES),
        )
        assertEquals(EXPECTED_MESSAGES, host.connections)
    }

    @Test
    fun `numbers CSeq across connections rather than restarting it`() {
        host = RtspHost()

        MoonlightRtspClient("127.0.0.1", host.port).handshake(1280, 720, 30)

        assertEquals((1..EXPECTED_MESSAGES).toList(), host.awaitCseqs(EXPECTED_MESSAGES))
    }

    @Test
    fun `gives up when the host refuses a step`() {
        host = RtspHost(refuse = "SETUP")

        assertNull(MoonlightRtspClient("127.0.0.1", host.port).handshake(1280, 720, 30))
        // Stopped at the refusal instead of carrying on with the rest.
        assertEquals(listOf("OPTIONS", "DESCRIBE", "SETUP"), host.awaitCommands(3))
    }

    @Test
    fun `gives up when the host hangs up without answering`() {
        host = RtspHost(silent = true)

        assertNull(MoonlightRtspClient("127.0.0.1", host.port).handshake(1280, 720, 30))
    }

    private companion object {
        const val EXPECTED_MESSAGES = 7
    }
}
