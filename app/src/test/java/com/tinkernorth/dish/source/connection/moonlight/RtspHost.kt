// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The RTSP half of a Moonlight host on loopback. One message per accepted connection, then close,
 * and replies that mirror what a real host sends, including a DESCRIBE body with no Content-length.
 * The SETUP replies name [controlPort], [videoPort] and [audioPort] as the stream ports.
 */
internal class RtspHost(
    private val refuse: String? = null,
    private val silent: Boolean = false,
    private val controlPort: Int = CONTROL_PORT,
    private val videoPort: Int = VIDEO_PORT,
    private val audioPort: Int = AUDIO_PORT,
) : Closeable {
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
            cseq == AUDIO_CSEQ -> head + "Transport: server_port=$audioPort\r\nX-SS-Ping-Payload: $PING\r\n\r\n"
            cseq == VIDEO_CSEQ -> head + "Transport: server_port=$videoPort\r\nX-SS-Ping-Payload: $PING\r\n\r\n"
            else -> head + "Transport: server_port=$controlPort\r\nX-SS-Connect-Data: $CONNECT_DATA\r\n\r\n"
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

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        const val PING = "9A615601970AEC19"
        const val CONNECT_DATA = "4270471497"
        const val CONTROL_PORT = 47999
        const val VIDEO_PORT = 47998
        const val AUDIO_PORT = 48000
        private const val BACKLOG = 8
        private const val AUDIO_CSEQ = 3
        private const val VIDEO_CSEQ = 4
        private const val TIMEOUT_S = 10L
        private const val POLL_MS = 10L
    }
}
