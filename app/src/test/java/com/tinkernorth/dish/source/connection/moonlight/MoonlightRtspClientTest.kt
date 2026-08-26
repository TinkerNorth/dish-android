// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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

        assertEquals(MoonlightRtspClient.StreamPorts(47999, 47998, 48000, 4270471497L.toInt(), PING), ports)
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

    /**
     * One message per accepted connection, then close. Replies mirror what a
     * real host sends, including a DESCRIBE body with no Content-length.
     */
    private class RtspHost(
        private val refuse: String? = null,
        private val silent: Boolean = false,
    ) {
        private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        private val commands = CopyOnWriteArrayList<String>()
        private val cseqs = CopyOnWriteArrayList<Int>()

        @Volatile var connections = 0
            private set

        val port: Int get() = server.localPort

        init {
            Thread {
                runCatching {
                    while (true) serve(server.accept())
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun serve(socket: Socket) {
            socket.use {
                connections += 1
                val head = readHead(socket)
                val command = head.substringBefore(' ')
                val cseq =
                    Regex("(?i)cseq:\\s*(\\d+)")
                        .find(head)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: 0
                if (silent) return
                readBody(socket, head)
                socket.getOutputStream().apply {
                    write(reply(command, cseq).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                cseqs += cseq
                commands += command
            }
        }

        // The client sends the whole message in one write, so the ANNOUNCE body
        // is already behind the head; drain it so the reply is not a race.
        private fun readBody(
            socket: Socket,
            head: String,
        ) {
            val declared =
                Regex("(?i)content-length:\\s*(\\d+)")
                    .find(head)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: return
            val input = socket.getInputStream()
            repeat(declared) { if (input.read() < 0) return }
        }

        private fun reply(
            command: String,
            cseq: Int,
        ): String {
            if (command == refuse) return "RTSP/1.0 500 INTERNAL SERVER ERROR\r\nCSeq: $cseq\r\n\r\n"
            val head = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n"
            return when {
                // No Content-length: the body runs to the close, as a real host sends it.
                command == "DESCRIBE" -> head + "\r\n" + "a=x-nv-video[0].refPicInvalidation:1\n"
                command != "SETUP" -> head + "\r\n"
                cseq == AUDIO_CSEQ -> head + "Transport: server_port=48000\r\nX-SS-Ping-Payload: $PING\r\n\r\n"
                cseq == VIDEO_CSEQ -> head + "Transport: server_port=47998\r\nX-SS-Ping-Payload: $PING\r\n\r\n"
                else -> head + "Transport: server_port=47999\r\nX-SS-Connect-Data: 4270471497\r\n\r\n"
            }
        }

        private fun readHead(socket: Socket): String {
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                head.append(b.toChar())
            }
            return head.toString()
        }

        fun awaitCommands(count: Int): List<String> {
            waitFor(count)
            return commands.toList()
        }

        fun awaitCseqs(count: Int): List<Int> {
            waitFor(count)
            return cseqs.toList()
        }

        private fun waitFor(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
            while (commands.size < count && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        }

        fun close() {
            runCatching { server.close() }
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val EXPECTED_MESSAGES = 7
        const val AUDIO_CSEQ = 3
        const val VIDEO_CSEQ = 4
        const val PING = "9A615601970AEC19"
        const val TIMEOUT_S = 10L
        const val POLL_MS = 10L
    }
}
