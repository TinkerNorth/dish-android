// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives [MoonlightPlainHttpClient] against a loopback [ServerSocket] so the
 * request bytes it puts on the wire and the responses it accepts are both real.
 * The fixture answers one request and records what it was asked.
 */
class MoonlightPlainHttpClientTest {
    private lateinit var server: ServerSocket
    private var serverThread: Thread? = null

    @Volatile private var requestHead: String = ""
    private val served = CountDownLatch(1)

    @After
    fun tearDown() {
        serverThread?.interrupt()
        if (::server.isInitialized) server.close()
    }

    /** Starts a one-shot host that replies with [respond] and records the request. */
    private fun host(respond: (OutputStream) -> Unit): String {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverThread =
            Thread {
                runCatching {
                    server.accept().use { socket ->
                        requestHead = readHead(socket)
                        respond(socket.getOutputStream())
                        socket.getOutputStream().flush()
                    }
                }
                served.countDown()
            }.apply {
                isDaemon = true
                start()
            }
        return "http://127.0.0.1:${server.localPort}/pair?devicename=roth&phrase=getservercert"
    }

    // Reads exactly the request head, so the fixture never blocks on a body.
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

    private fun client() = MoonlightPlainHttpClient(TIMEOUT, TIMEOUT)

    private fun OutputStream.send(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `formats a GET the host can route, with the port in the Host header`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi") }
        client().get(url)
        served.await(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)

        val lines = requestHead.split("\r\n")
        assertEquals("GET /pair?devicename=roth&phrase=getservercert HTTP/1.1", lines[0])
        assertTrue(requestHead, lines.contains("Host: 127.0.0.1:${server.localPort}"))
        assertTrue(requestHead, lines.contains("Connection: close"))
        assertTrue("head must end with a blank line", requestHead.endsWith("\r\n\r\n"))
    }

    @Test
    fun `reads a Content-Length body`() {
        val body = "<root status_code=\"200\"><plaincert>abcd</plaincert></root>"
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${body.length}\r\n\r\n$body") }

        val reply = client().get(url)

        assertEquals(200, reply.status)
        assertEquals(body, reply.body)
        assertTrue(reply.ok)
    }

    @Test
    fun `a body longer than Content-Length is cut at the declared length`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nkeepDROP") }

        assertEquals("keep", client().get(url).body)
    }

    @Test
    fun `reads a body delimited by the connection close`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\nno-length-here") }

        val reply = client().get(url)

        assertEquals(200, reply.status)
        assertEquals("no-length-here", reply.body)
    }

    @Test
    fun `reads a chunked body`() {
        val url =
            host {
                it.send(
                    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nhello\r\n" +
                        "6\r\n world\r\n" +
                        "0\r\n\r\n",
                )
            }

        val reply = client().get(url)

        assertEquals(200, reply.status)
        assertEquals("hello world", reply.body)
    }

    @Test
    fun `chunked wins over a Content-Length the host also sent`() {
        val url =
            host {
                it.send("HTTP/1.1 200 OK\r\nContent-Length: 99\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n")
            }

        assertEquals("ok", client().get(url).body)
    }

    @Test
    fun `header lookup is case-insensitive`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\ncOnTeNt-LeNgTh: 3\r\n\r\nyes") }

        assertEquals("yes", client().get(url).body)
    }

    @Test
    fun `surfaces a non-2xx status with its body`() {
        val url = host { it.send("HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nno-such-x") }

        val reply = client().get(url)

        assertEquals(404, reply.status)
        assertEquals("no-such-x", reply.body)
        assertTrue(!reply.ok)
    }

    @Test
    fun `a body cut short keeps the real status and returns what arrived`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Length: 64\r\n\r\nonly-this-much") }

        val reply = client().get(url)

        assertEquals(200, reply.status)
        assertEquals("only-this-much", reply.body)
    }

    @Test
    fun `a head cut off mid-line is unreachable, not a crash`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Len") }

        val reply = client().get(url)

        assertEquals(0, reply.status)
        assertEquals("", reply.body)
        assertTrue(reply.unreachable)
    }

    @Test
    fun `a reply that is not HTTP at all is unreachable`() {
        val url = host { it.send("GARBAGE\r\n\r\nbody") }

        assertEquals(0, client().get(url).status)
    }

    @Test
    fun `a host that closes without answering is unreachable`() {
        val url = host { /* accept, then drop */ }

        assertEquals(0, client().get(url).status)
    }

    @Test
    fun `a host that never answers times out into an unreachable reply`() {
        // Accept the connection and hold it: the read timeout must fire, and it
        // must surface as Reply(0, "") rather than a SocketTimeoutException.
        val url = host { Thread.sleep(SLOW_MS) }
        val client = MoonlightPlainHttpClient(TIMEOUT, READ_TIMEOUT_SHORT)

        val started = System.nanoTime()
        val reply = client.get(url)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, reply.status)
        assertTrue("should give up near the read timeout, took ${elapsedMs}ms", elapsedMs < SLOW_MS)
    }

    @Test
    fun `a per-call read timeout outlasts a host that answers slowly`() {
        // Pairing phase 1 is held open until a human types the PIN, so the caller
        // raises the read timeout for it. The default would give up here.
        val url =
            host {
                Thread.sleep(HELD_MS)
                it.send("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\npin!")
            }
        val client = MoonlightPlainHttpClient(TIMEOUT, READ_TIMEOUT_SHORT)

        val reply = client.get(url, readTimeoutMs = TIMEOUT)

        assertEquals(200, reply.status)
        assertEquals("pin!", reply.body)
    }

    @Test
    fun `a refused connection is unreachable`() {
        // Bind then close, so the port is almost certainly free and refusing.
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = dead.localPort
        dead.close()

        assertEquals(0, client().get("http://127.0.0.1:$port/serverinfo?uniqueid=x").status)
    }

    @Test
    fun `an unparseable url is unreachable rather than an exception`() {
        assertEquals(0, client().get("http://[not a url/pair").status)
    }

    private companion object {
        const val TIMEOUT = 4_000
        const val READ_TIMEOUT_SHORT = 300
        const val SLOW_MS = 3_000L
        const val HELD_MS = 900L
    }
}
