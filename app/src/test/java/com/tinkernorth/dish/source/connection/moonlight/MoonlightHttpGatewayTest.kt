// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.ThrowawayIdentity
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.sha256FingerprintHex
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Drives [MoonlightHttpGateway.getHttps] against a real loopback TLS host that
 * demands a client certificate, so the handshake, the bytes on the wire and the
 * socket lifecycle are all real.
 *
 * What this pins down is the shape the gateway has to keep: one connection per
 * request, closed once the host has answered; a full handshake on every one of
 * them, never a resumed session; and a host certificate that has to survive the
 * TOFU pin before any request is written. Real Sunshine hosts have broken on
 * each of the first two in turn, the pooled HttpsURLConnection version by
 * leaking a live session per call and the cached-factory version by offering
 * one to resume.
 */
class MoonlightHttpGatewayTest {
    private val clientHeld = ThrowawayIdentity.heldCertificate("dish-gateway-test-client")
    private val hostHeld = ThrowawayIdentity.heldCertificate("Sunshine Gamestream Host")
    private val impostorHeld = ThrowawayIdentity.heldCertificate("Sunshine Gamestream Host")

    private val identity: MoonlightIdentity = ThrowawayIdentity.of(clientHeld)

    private val pinned = mutableMapOf<String, String>()
    private val pins =
        mockk<SatellitePinRepository> {
            every { pinnedFingerprint(any()) } answers { pinned[firstArg()] }
            val id = slot<String>()
            val fingerprint = slot<String>()
            every { pin(capture(id), capture(fingerprint)) } answers { pinned[id.captured] = fingerprint.captured }
        }

    private lateinit var host: TlsHost

    @After
    fun tearDown() {
        if (::host.isInitialized) host.close()
    }

    private fun gateway() = MoonlightHttpGateway(identity, pins)

    private fun start(held: HeldCertificate = hostHeld): String {
        host = TlsHost(held, clientHeld.certificate)
        return "https://127.0.0.1:${host.port}"
    }

    @Test
    fun `presents the client certificate and hands back the parsed reply`() {
        val base = start()

        val reply = gateway().getHttps("$base/serverinfo?uniqueid=abc", HOST_ID)

        assertEquals(200, reply.status)
        assertEquals(BODY, reply.body)
        assertTrue(reply.ok)
        assertEquals("CN=dish-gateway-test-client", host.awaitClientPrincipals().single())
    }

    @Test
    fun `asks the host to close the connection once it has answered`() {
        val base = start()

        gateway().getHttps("$base/applist?uniqueid=abc", HOST_ID)

        val head = host.awaitHeads().single()
        assertEquals("GET /applist?uniqueid=abc HTTP/1.1", head.lines().first())
        assertTrue(head, head.lines().contains("Connection: close"))
    }

    @Test
    fun `every call gets its own connection, and closes it before returning`() {
        val base = start()
        val gateway = gateway()

        repeat(CALLS) { assertEquals(200, gateway.getHttps("$base/serverinfo?uniqueid=abc", HOST_ID).status) }

        // One accept per call, and the host read EOF on each: nothing of ours is
        // still open, which is exactly what the pooled URL-stack version leaked.
        assertEquals(CALLS, host.awaitHeads(CALLS).size)
        assertEquals(CALLS, host.closedByPeer.size)
    }

    @Test
    fun `handshakes from scratch every call, never offering a session to resume`() {
        val base = start()
        val gateway = gateway()

        repeat(CALLS) { assertEquals(200, gateway.getHttps("$base/serverinfo?uniqueid=abc", HOST_ID).status) }

        assertEquals(CALLS, host.awaitHeads(CALLS).size)
        // One client-certificate check per call. A resumed session skips the
        // client's Certificate message altogether, so a host that authorises by
        // that certificate never sees it: Sunshine answers such a connection
        // with a fatal internal_error alert and logs nothing at all. The gateway
        // must therefore never carry a session cache from one call to the next.
        assertEquals(CALLS, host.clientChecks.get())
    }

    @Test
    fun `pins the host certificate on first contact`() {
        val base = start()

        gateway().getHttps("$base/serverinfo?uniqueid=abc", HOST_ID)

        assertEquals(sha256FingerprintHex(hostHeld.certificate.encoded), pinned[HOST_ID])
    }

    @Test
    fun `keeps talking to a host whose certificate still matches its pin`() {
        val base = start()
        pinned[HOST_ID] = sha256FingerprintHex(hostHeld.certificate.encoded)

        assertEquals(200, gateway().getHttps("$base/serverinfo?uniqueid=abc", HOST_ID).status)
    }

    @Test
    fun `refuses a host whose certificate does not match the pin, without sending the request`() {
        val base = start(impostorHeld)
        pinned[HOST_ID] = sha256FingerprintHex(hostHeld.certificate.encoded)

        val reply = gateway().getHttps("$base/serverinfo?uniqueid=abc", HOST_ID)

        assertEquals(0, reply.status)
        assertTrue(reply.unreachable)
        // The pin is checked once the handshake has produced the peer certificate,
        // so the host sees a connection; what it must never see is a request.
        assertTrue("a rejected host must never see the request", host.headOrNull().isNullOrEmpty())
        // The stored pin is the real host's; a mismatch must not overwrite it.
        assertEquals(sha256FingerprintHex(hostHeld.certificate.encoded), pinned[HOST_ID])
    }

    /**
     * A loopback TLS host that requires a client certificate, answers every
     * request the same way, and records what it saw.
     */
    private class TlsHost(
        held: HeldCertificate,
        trustedClient: X509Certificate,
    ) {
        private val credentials =
            HandshakeCertificates
                .Builder()
                .heldCertificate(held)
                .addTrustedCertificate(trustedClient)
                .build()

        /** One per full handshake, none on a resumed session. See [CountingTrustManager]. */
        val clientChecks = AtomicInteger()

        private val server: SSLServerSocket =
            SSLContext
                .getInstance("TLS")
                .apply {
                    init(
                        arrayOf<KeyManager>(credentials.keyManager),
                        arrayOf<TrustManager>(CountingTrustManager(credentials.trustManager, clientChecks)),
                        null,
                    )
                }.serverSocketFactory
                .createServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1")) as SSLServerSocket

        val heads = CopyOnWriteArrayList<String>()
        val clientPrincipals = CopyOnWriteArrayList<String>()
        val closedByPeer = CopyOnWriteArrayList<Boolean>()
        private val served = CountDownLatch(CALLS)

        val port: Int get() = server.localPort

        init {
            server.needClientAuth = true
            Thread {
                runCatching {
                    while (true) serve(server.accept() as SSLSocket)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun serve(socket: SSLSocket) {
            socket.use {
                runCatching {
                    clientPrincipals += socket.session.peerPrincipal.name
                    heads += readHead(socket)
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: ${BODY.length}\r\n\r\n$BODY")
                                .toByteArray(Charsets.ISO_8859_1),
                        )
                        flush()
                    }
                    // The client asked us to close, so it must not send anything
                    // more: what comes back has to be end-of-stream.
                    closedByPeer += socket.getInputStream().read() < 0
                }
                served.countDown()
            }
        }

        private fun readHead(socket: SSLSocket): String {
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                head.append(b.toChar())
            }
            return head.toString()
        }

        fun awaitHeads(count: Int = 1): List<String> {
            waitFor(count)
            return heads.toList()
        }

        fun awaitClientPrincipals(): List<String> {
            waitFor(1)
            return clientPrincipals.toList()
        }

        /** What the host saw, once it is clear it will not see anything more. */
        fun headOrNull(): String? {
            served.await(SETTLE_MS, TimeUnit.MILLISECONDS)
            return heads.firstOrNull()
        }

        private fun waitFor(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
            while (heads.size < count && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        }

        fun close() {
            runCatching { server.close() }
        }
    }

    /**
     * Counts what a Moonlight host's own verify callback counts: JSSE runs one
     * client-certificate check per full handshake and none at all on a resumed
     * session, because a resumed session carries the peer identity forward
     * instead of asking for the certificate again.
     */
    private class CountingTrustManager(
        private val delegate: X509TrustManager,
        private val checks: AtomicInteger,
    ) : X509TrustManager by delegate {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
            checks.incrementAndGet()
            delegate.checkClientTrusted(chain, authType)
        }
    }

    private companion object {
        const val HOST_ID = "moonlight:127.0.0.1"
        const val BODY = "<root status_code=\"200\"><PairStatus>1</PairStatus></root>"
        const val BACKLOG = 4
        const val CALLS = 3
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 10L
    }
}
