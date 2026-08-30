// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * B23, against the real lock and real threads. Every other suite here runs the
 * manager on a single test dispatcher, where a Mutex is never contended and its
 * removal would change nothing: this one puts several bindings on Dispatchers.IO
 * and arrives at the same host at the same moment, which is the only shape in
 * which the lock does any work.
 *
 * The measurement is the peak number of converges inside the host calls at once.
 * Two converges overlapping is exactly two sessions being opened on a host that
 * can hold one, so the assertion is that the peak never rises above one.
 */
class MoonlightConvergeLockTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var manager: MoonlightConnectionManager

    private val remembered =
        RememberedMoonlight(
            id = "moonlight:uid:abc",
            name = "PC",
            address = "10.0.0.5",
            uniqueId = "abc",
            lastAppId = "1",
            lastAppName = "Desktop",
        )

    private val serverInfo =
        """<root status_code="200"><hostname>PC</hostname><uniqueid>abc</uniqueid>
           <PairStatus>1</PairStatus><currentgame>0</currentgame></root>"""

    private val appList =
        """<root status_code="200"><App><AppTitle>Desktop</AppTitle><ID>1</ID></App></root>"""

    // Refused in the body, so no socket is opened and every pass leaves the host
    // idle again, which is what makes a second converge want to open one too.
    private val refusedLaunch = """<root status_code="401" status_message="Unauthorized"/>"""

    /** How deep inside the probe-and-launch stretch the converges are, right now. */
    private val inside = AtomicInteger()

    private val peak = AtomicInteger()

    private val launches = AtomicInteger()

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    private fun pad(slotId: String) =
        MoonlightPadRequest(
            slotId = slotId,
            emulatedType = MoonlightEmulatedType.XBOX,
            capabilities = 0x03,
            supportedButtons = 0xFFFF,
        )

    // Wide enough that two converges running loose would be caught overlapping, and
    // narrow enough that the serialised run this asserts stays quick.
    private fun enter() {
        val depth = inside.incrementAndGet()
        peak.updateAndGet { seen -> maxOf(seen, depth) }
        Thread.sleep(DWELL_MS)
    }

    private fun leave() = inside.decrementAndGet()

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "0123456789abcdef"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = mockk(relaxed = true)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } answers {
            enter()
            reply(serverInfo)
        }
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns reply(serverInfo)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns reply(appList)
        every { gateway.getHttps(match { it.contains("/launch") }, any()) } answers {
            launches.incrementAndGet()
            leave()
            reply(refusedLaunch)
        }

        val store = mockk<com.tinkernorth.dish.repository.RememberedMoonlightRepository>(relaxed = true)
        every { store.get(remembered.id) } returns remembered
        every { store.entries } returns MutableStateFlow(listOf(remembered))

        manager =
            MoonlightConnectionManager(
                context = context,
                scope = scope,
                ioDispatcher = Dispatchers.IO,
                discovery = mockk(relaxed = true),
                gateway = gateway,
                identity = mockk<MoonlightIdentity>(relaxed = true),
                store = store,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `bindings arriving at once never open two sessions on one host`() {
        val start = CountDownLatch(1)
        val threads =
            (0 until RACERS).map { n ->
                thread(name = "binding-$n") {
                    start.await()
                    manager.applyDesired(mapOf(remembered.id to (0..n).map { pad("slot-$it") }))
                }
            }

        start.countDown()
        threads.forEach { it.join(JOIN_MS) }

        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (launches.get() < RACERS && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)

        assertEquals("every binding's converge has to have run", RACERS, launches.get())
        assertEquals("two converges must never be inside the same host at once", 1, peak.get())
        assertTrue("the host is left holding one session's worth of pads", manager.get(remembered.id)!!.padCount <= 4)
    }

    private companion object {
        const val RACERS = 6
        const val DWELL_MS = 30L
        const val JOIN_MS = 10_000L
        const val SETTLE_MS = 20_000L
        const val POLL_MS = 10L
    }
}
