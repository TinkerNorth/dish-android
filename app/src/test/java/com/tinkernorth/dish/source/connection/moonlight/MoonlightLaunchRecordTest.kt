// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a session that comes up writes about its host. The host is on loopback and answers the
 * launch, the RTSP handshake and the control channel's handshake the way a real one does, so the
 * session goes live through every step it takes against one.
 */
class MoonlightLaunchRecordTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val control = EnetHandshakeHost()
    private val video = DatagramSocket(0, loopback)
    private val audio = DatagramSocket(0, loopback)
    private val rtsp = RtspHost(controlPort = control.port, videoPort = video.localPort, audioPort = audio.localPort)

    // Sessions run on worker threads, as in the app: the RTSP and control handshakes block on sockets.
    private val sessions = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val rows = ConcurrentHashMap<String, RememberedMoonlight>()

    // Counted down by the record a session writes once it is live, the only write that picks app "1".
    private val sessionRecorded = CountDownLatch(1)
    private val entries = MutableStateFlow<List<RememberedMoonlight>>(emptyList())
    private val host = MoonlightHost(name = "PC", address = "127.0.0.1")
    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var manager: MoonlightConnectionManager

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    @Before
    fun setUp() {
        gateway = mockk(relaxed = true)
        answerEveryLineAlike(gateway)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(REBUILT_INFO)
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns reply(REBUILT_TRUSTING_INFO)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns reply(APP_LIST)
        every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns reply(launchReply())
        every { gateway.getHttps(match { it.contains("/cancel") }, any()) } returns reply(CANCELLED)

        val store = mockk<RememberedMoonlightRepository>(relaxed = true)
        every { store.get(any()) } answers { rows[firstArg<String>()] }
        every { store.all() } answers { rows.values.toList() }
        every { store.entries } returns entries
        every { store.put(any<RememberedMoonlight>()) } answers {
            val row = firstArg<RememberedMoonlight>()
            rows[row.id] = row
            entries.value = rows.values.toList()
            if (row.lastAppId == "1") sessionRecorded.countDown()
        }
        every { store.remove(any<String>()) } answers {
            rows.remove(firstArg<String>())
            entries.value = rows.values.toList()
        }

        manager =
            MoonlightConnectionManager(
                context = contextWithADeviceId(),
                scope = sessions,
                ioDispatcher = Dispatchers.IO,
                discovery = mockk(relaxed = true),
                gateway = gateway,
                identity = mockk<MoonlightIdentity>(relaxed = true),
                store = store,
            )
    }

    @After
    fun tearDown() {
        sessions.cancel()
        recorder.cancel()
        rtsp.close()
        control.close()
        video.close()
        audio.close()
    }

    private fun contextWithADeviceId(): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "7b5d0738cbb54d3e"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    // MOON-D6. "Pair again" on a host reported as replaced records the machine that answers now. The
    // session that followed wrote the machine before back over it, from the host its binding had held
    // since before the pairing, and the next time the host was asked it read as replaced again.
    @Test
    fun `a session on a host paired again keeps the uniqueid the pairing recorded`() {
        rows[host.id] = RememberedMoonlight(id = host.id, name = "PC", address = host.address, uniqueId = "host-1", paired = true)
        val seen = recordEvents()
        manager.applyDesired(mapOf(host.id to listOf(PAD)))
        assertTrue("the host is reported as replaced", within(WAIT_MS) { seen.any { it is MoonlightConnectionEvent.HostReplaced } })

        val pairedAgain = runBlocking { manager.pairHost(manager.rememberedHost(host.id)!!) }
        manager.retrySessions()

        assertTrue(pairedAgain)
        assertTrue("the session comes up", within(WAIT_MS) { rows[host.id]?.lastAppId == "1" })
        assertEquals("host-2", rows.getValue(host.id).uniqueId)
    }

    // B6. A forget waits for a session coming up on the same host and then takes it down with the
    // rest. It used to run beside it, and the session, once live, wrote the record straight back.
    @Test
    fun `a host forgotten while its session comes up stays forgotten`() {
        rows[host.id] = RememberedMoonlight(id = host.id, name = "PC", address = host.address, uniqueId = "host-2", paired = true)
        val launchAsked = CountDownLatch(1)
        val launchAnswer = CompletableFuture<MoonlightHttpGateway.Reply>()
        every { gateway.getHttps(match { it.contains("/launch") }, any()) } answers {
            launchAsked.countDown()
            launchAnswer.join()
        }
        manager.applyDesired(mapOf(host.id to listOf(PAD)))
        assertTrue("the launch is on the wire", launchAsked.await(WAIT_MS, TimeUnit.MILLISECONDS))

        manager.forget(host.id)
        launchAnswer.complete(reply(launchReply()))

        assertTrue("the session came up", sessionRecorded.await(WAIT_MS, TimeUnit.MILLISECONDS))
        assertTrue("and the host stays forgotten", within(WAIT_MS) { rows[host.id] == null && manager.get(host.id) == null })
    }

    // A forget cancels the host's session under the converge lock, which takes as long as the host
    // takes to answer. A probe that began meanwhile read the epoch the forget had already moved on
    // to, and once the host answered it, after the forget was over, wrote the host back as verified.
    @Test
    fun `a host probed while its forget waits on the host stays forgotten`() {
        rows[host.id] = RememberedMoonlight(id = host.id, name = "PC", address = host.address, uniqueId = "host-2", paired = true)
        manager.applyDesired(mapOf(host.id to listOf(PAD)))
        assertTrue("the session is live", sessionRecorded.await(WAIT_MS, TimeUnit.MILLISECONDS))
        assertTrue("and published", within(WAIT_MS) { host.id in manager.sessionHostIds.value })
        val cancelAsked = CountDownLatch(1)
        val cancelAnswer = CompletableFuture<MoonlightHttpGateway.Reply>()
        every { gateway.getHttps(match { it.contains("/cancel") }, any()) } answers {
            cancelAsked.countDown()
            cancelAnswer.join()
        }
        val probeAnswer = CompletableFuture<MoonlightHttpGateway.Reply>()
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } answers { probeAnswer.join() }

        manager.forget(host.id)
        assertTrue("the cancel is on the wire", cancelAsked.await(WAIT_MS, TimeUnit.MILLISECONDS))
        val probe = sessions.async { manager.probe(host) }
        cancelAnswer.complete(reply(CANCELLED))
        assertTrue("the forget is over", within(WAIT_MS) { host.id !in manager.sessionHostIds.value })
        probeAnswer.complete(reply(REBUILT_TRUSTING_INFO))

        assertEquals(MoonlightTrustState.NOT_PAIRED, runBlocking { probe.await() }.trust)
        assertFalse("the host is not verified", host.id in manager.verifiedHostIds.value)
    }

    private fun launchReply() =
        """<root status_code="200"><sessionUrl0>rtsp://127.0.0.1:${rtsp.port}</sessionUrl0><gamesession>1</gamesession></root>"""

    private fun recordEvents(): List<MoonlightConnectionEvent> {
        val seen = CopyOnWriteArrayList<MoonlightConnectionEvent>()
        manager.events
            .onEach(seen::add)
            .launchIn(recorder)
        return seen
    }

    private fun within(
        millis: Long,
        holds: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!holds() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return holds()
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
        val PAD = MoonlightPadRequest(slotId = "a", emulatedType = 1, capabilities = 0x03, supportedButtons = 0xFFFF)

        // The machine now behind the address, which still trusts this device.
        const val REBUILT_INFO =
            """<root status_code="200"><hostname>PC</hostname><uniqueid>host-2</uniqueid><PairStatus>0</PairStatus></root>"""
        const val REBUILT_TRUSTING_INFO =
            """<root status_code="200"><hostname>PC</hostname><uniqueid>host-2</uniqueid><PairStatus>1</PairStatus>
               <currentgame>0</currentgame></root>"""
        const val APP_LIST = """<root status_code="200"><App><AppTitle>Desktop</AppTitle><ID>1</ID></App></root>"""
        const val CANCELLED = """<root status_code="200"><cancel>1</cancel></root>"""
    }
}
