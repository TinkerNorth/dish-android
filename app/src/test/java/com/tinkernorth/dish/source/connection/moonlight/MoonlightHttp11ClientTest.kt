// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Drives [MoonlightHttp11Client] against a loopback [ServerSocket] so the
 * request bytes it puts on the wire and the responses it accepts are both real.
 * The fixture answers one request and records what it was asked.
 */
class MoonlightHttp11ClientTest {
    private lateinit var server: ServerSocket
    private var serverThread: Thread? = null

    @Volatile private var requestHead: String = ""
    private val served = CountDownLatch(1)

    // A holding host's side of the story: it has the request, and later, the connection closing under it.
    private val holding = CountDownLatch(1)
    private val closedUnderIt = CountDownLatch(1)

    @Volatile private var heldConnection: Socket? = null

    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        callers.cancel()
        heldConnection?.close()
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

    /**
     * Starts a one-shot host that holds the request it is sent and never answers it, the way a
     * host holds pairing phase 1 until a human types the PIN, and notes the client closing it.
     */
    private fun holdingHost(): String {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverThread =
            Thread { runCatching { server.accept().use(::holdUntilClosed) } }.apply {
                isDaemon = true
                start()
            }
        return "http://127.0.0.1:${server.localPort}/pair?devicename=roth&phrase=getservercert"
    }

    // The client sent nothing more, so the next read ends only when the client's side of the connection closes.
    private fun holdUntilClosed(connection: Socket) {
        heldConnection = connection
        requestHead = readHead(connection)
        holding.countDown()
        runCatching { connection.getInputStream().read() }
        closedUnderIt.countDown()
    }

    private fun endsWithin(
        job: Job,
        millis: Long,
    ): Boolean = runBlocking { withTimeoutOrNull(millis) { job.join() } } != null

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

    private fun client() = MoonlightHttp11Client(TIMEOUT, TIMEOUT)

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
        val client = MoonlightHttp11Client(TIMEOUT, READ_TIMEOUT_SHORT)

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
        val client = MoonlightHttp11Client(TIMEOUT, READ_TIMEOUT_SHORT)

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

    @Test
    fun `hands the connected socket to the upgrade hook with the host it dialled`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi") }
        var seen: Pair<String, Int>? = null
        val client =
            MoonlightHttp11Client(TIMEOUT, TIMEOUT) { socket, host, port ->
                seen = host to port
                socket
            }

        val reply = client.get(url)

        assertEquals(200, reply.status)
        assertEquals("127.0.0.1" to server.localPort, seen)
    }

    @Test
    fun `an upgrade that rejects the host is unreachable, and the host is never asked`() {
        // How the gateway refuses a certificate that fails its pin: it throws out
        // of the hook, so the request must never reach the wire.
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi") }
        val client =
            MoonlightHttp11Client(TIMEOUT, TIMEOUT) { _, _, _ ->
                throw SSLPeerUnverifiedException("cert pin mismatch")
            }

        val reply = client.get(url)

        served.await(SETTLE_MS, TimeUnit.MILLISECONDS)
        assertEquals(0, reply.status)
        assertTrue(reply.unreachable)
        assertEquals("", requestHead)
    }

    // Pairing phase 1 waits two minutes for a human; a Cancel must not.
    @Test
    fun `a request hung up while the host holds it ends at once, unanswered, and the host sees it close`() {
        val url = holdingHost()
        val line = CallLine()
        val answer = CompletableFuture.supplyAsync { client().get(url, HELD_READ_MS, line) }
        assertTrue("the host is holding the request", holding.await(HOLD_WAIT_MS, TimeUnit.MILLISECONDS))

        line.hangUp()

        assertEquals(0, answer.get(STOP_MS, TimeUnit.MILLISECONDS).status)
        assertTrue("the host is left a closed connection", closedUnderIt.await(STOP_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a line hung up before its request dials never reaches the host`() {
        val url = holdingHost()
        val line = CallLine()
        line.hangUp()

        val reply = client().get(url, HELD_READ_MS, line)

        assertEquals(0, reply.status)
        assertFalse("the host was never asked", holding.await(SETTLE_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `cancelling the caller hangs up the request it is waiting on`() {
        val url = holdingHost()
        val caller = callers.launch { hangingUpOnCancel { line -> client().get(url, HELD_READ_MS, line) } }
        assertTrue("the host is holding the request", holding.await(HOLD_WAIT_MS, TimeUnit.MILLISECONDS))

        caller.cancel()

        assertTrue("the caller ends without the host answering", endsWithin(caller, STOP_MS))
        assertTrue("the host is left a closed connection", closedUnderIt.await(STOP_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a cancelled caller never sees the reply its hung-up line left behind`() {
        val url = holdingHost()
        val handedBack = AtomicReference<MoonlightHttpGateway.Reply?>()
        val caller =
            callers.launch {
                handedBack.set(hangingUpOnCancel { line -> client().get(url, HELD_READ_MS, line) })
            }
        assertTrue("the host is holding the request", holding.await(HOLD_WAIT_MS, TimeUnit.MILLISECONDS))

        caller.cancel()

        assertTrue(endsWithin(caller, STOP_MS))
        assertNull("the unanswered reply is not handed back", handedBack.get())
    }

    // A call that throws once its socket is closed under it must not hand that throw to a caller
    // that was cancelled: the caller asked to stop, and a failure it did not cause is not an answer.
    @Test
    fun `a cancelled caller gets its cancellation even when the hung-up call throws`() {
        val waiting = CountDownLatch(1)
        val outcome = CompletableFuture<Throwable>()
        val caller =
            callers.launch {
                runCatching { hangingUpOnCancel { line -> throwOnceHungUp(line, waiting) } }
                    .onFailure { outcome.complete(it) }
            }
        assertTrue("the call is waiting", waiting.await(HOLD_WAIT_MS, TimeUnit.MILLISECONDS))

        caller.cancel()

        assertTrue(outcome.get(STOP_MS, TimeUnit.MILLISECONDS) is CancellationException)
    }

    // Waits on its line until it is hung up, then fails the way a read fails on a closed socket.
    private fun throwOnceHungUp(
        line: CallLine,
        waiting: CountDownLatch,
    ): Nothing {
        val hungUp = CountDownLatch(1)
        line.attach { hungUp.countDown() }
        waiting.countDown()
        hungUp.await()
        error("the socket closed under the read")
    }

    @Test
    fun `a request answered before any Cancel comes back whole`() {
        val url = host { it.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi") }

        val reply = runBlocking { hangingUpOnCancel { line -> client().get(url, TIMEOUT, line) } }

        assertEquals(200, reply.status)
        assertEquals("hi", reply.body)
    }

    private companion object {
        const val TIMEOUT = 4_000
        const val READ_TIMEOUT_SHORT = 300
        const val SLOW_MS = 3_000L
        const val HELD_MS = 900L
        const val SETTLE_MS = 500L

        // Far longer than any of these tests may take: only a hang-up ends a read this long in time.
        const val HELD_READ_MS = 60_000
        const val STOP_MS = 2_000L
        const val HOLD_WAIT_MS = 5_000L
    }
}
