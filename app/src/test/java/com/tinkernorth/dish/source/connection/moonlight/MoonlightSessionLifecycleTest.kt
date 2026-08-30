// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a session that actually CAME UP does, which is the half the refusal suites
 * cannot reach: only a live session is cancelled when its last pad leaves, only a
 * live session can be joined without HTTP, and only a live session can drop.
 *
 * The seam is [MoonlightConnection.markLive], the same call the stream setup makes
 * once the control channel answers. The converge is queued BEFORE the session is
 * marked live, so it observes the live session the way the real one does and the
 * receive pump that follows cannot get in first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightSessionLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var store: com.tinkernorth.dish.repository.RememberedMoonlightRepository
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

    // The host refuses in the body, so the launch never reaches a socket and the
    // connection is left holding its pads with no stream. That is the point: the
    // stream is supplied below by hand.
    private val refusedLaunch = """<root status_code="401" status_message="Unauthorized"/>"""

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    private fun pad(slotId: String) =
        MoonlightPadRequest(
            slotId = slotId,
            emulatedType = MoonlightEmulatedType.XBOX,
            capabilities = 0x03,
            supportedButtons = 0xFFFF,
        )

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "0123456789abcdef"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = mockk(relaxed = true)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(serverInfo)
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns reply(serverInfo)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns reply(appList)
        every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns reply(refusedLaunch)
        every { gateway.getHttps(match { it.contains("/cancel") }, any()) } returns
            reply("""<root status_code="200"><cancel>1</cancel></root>""")

        store = mockk(relaxed = true)
        every { store.get(remembered.id) } returns remembered
        every { store.entries } returns MutableStateFlow(listOf(remembered))

        manager =
            MoonlightConnectionManager(
                context = context,
                scope = TestScope(dispatcher),
                ioDispatcher = dispatcher,
                discovery = mockk(relaxed = true),
                gateway = gateway,
                identity = mockk<MoonlightIdentity>(relaxed = true),
                store = store,
            )
    }

    private fun bind(vararg slotIds: String) {
        manager.applyDesired(mapOf(remembered.id to slotIds.map(::pad)))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun connection(): MoonlightConnection = manager.get(remembered.id)!!

    /**
     * Hand the connection a control stream, the way the stream setup does once ENet
     * answers. The receive pump it starts is queued behind whatever the caller has
     * already queued, so a converge asked for first still sees the session live.
     */
    private fun goLive(session: MoonlightControlSession = mockk(relaxed = true)): MoonlightControlSession {
        connection().markLive(session, remembered.lastAppId, remembered.lastAppName)
        assertEquals(MoonlightSessionState.Live, connection().state.value)
        return session
    }

    private fun TestScope.collectEvents(into: MutableList<MoonlightConnectionEvent>): Job {
        val job = launch { manager.events.toList(into) }
        dispatcher.scheduler.runCurrent()
        return job
    }

    private fun MockKMatcherScope.cancels() = gateway.getHttps(match { it.contains("/cancel") }, any())

    private fun MockKMatcherScope.launches() = gateway.getHttps(match { it.contains("/launch") }, any())

    // B14. The host started an app for us, so the last binding to leave takes it back down.
    @Test
    fun `the last pad leaving a live session tells the host to close the app`() =
        runTest(dispatcher) {
            bind("a")

            manager.applyDesired(emptyMap())
            goLive()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { cancels() }
            assertEquals(0, connection().padCount)
            assertEquals(MoonlightSessionState.Idle, connection().state.value)
            assertEquals(emptySet<String>(), manager.sessionHostIds.value)
        }

    // B13. One pad of several leaving is not the end of the session, and the host is
    // told which controller went by the active mask, not by a cancel.
    @Test
    fun `one pad of two leaving keeps the session up and closes nothing`() =
        runTest(dispatcher) {
            bind("a", "b")
            val launchesSoFar = 1

            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"))))
            val session = goLive()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { cancels() }
            verify(exactly = launchesSoFar) { launches() }
            assertEquals(1, connection().padCount)
            assertNotNull(connection().padFor("a"))
            assertNull(connection().padFor("b"))
            // Bit 1 cleared, bit 0 still set: the pad that stayed is still plugged in.
            verify { session.sendControllerState(0, 0b01, 0, 0, 0, 0, 0, 0, 0) }
        }

    // B8. A later binding on a live host is a controller arrival and nothing else. An
    // HTTP launch here would be a second session on a host that can only hold one.
    @Test
    fun `a second binding on a live session announces a pad and makes no HTTP call`() =
        runTest(dispatcher) {
            bind("a")

            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"), pad("b"))))
            val session = goLive()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { launches() }
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/resume") }, any()) }
            verify(exactly = 0) { cancels() }
            assertEquals(2, connection().padCount)
            assertEquals(1, connection().padFor("b")?.number)
            verify { session.sendControllerArrival(1, MoonlightEmulatedType.XBOX, 0x03, 0xFFFF) }
        }

    // B18. A control stream that stops without the host saying so is as likely to be a
    // blip as an ending, so the app is left running and the state says Dropped, which is
    // the state that offers Reconnect. Merging it with Ended would close somebody's game.
    @Test
    fun `a control link that drops leaves the app running and is not a host ending`() =
        runTest(dispatcher) {
            bind("a")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            goLive()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionState.Dropped, connection().state.value)
            verify(exactly = 0) { cancels() }
            assertTrue("a drop is not the host ending it", seen.none { it is MoonlightConnectionEvent.EndedByHost })
            assertEquals("the binding keeps its pad", 1, connection().padCount)
            collector.cancel()
        }

    // B17. The host ended it, so there is nothing to rejoin: the pads stay claimed by
    // their bindings and the next use opens a NEW session rather than resuming one.
    @Test
    fun `a host-ended session keeps its bindings and the next use starts a new one`() =
        runTest(dispatcher) {
            bind("a")
            connection().markEnded()
            assertEquals(1, connection().padCount)

            manager.retrySessions()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 2) { launches() }
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/resume") }, any()) }
            verify(exactly = 0) { cancels() }
            assertEquals(1, connection().padCount)
        }

    // B9. The host says the running session is ours by answering resume=1, and that answer
    // is never shown to the user: no PIN, no prompt, no refusal event, just the rejoin.
    @Test
    fun `a session this device already holds is rejoined without asking the user anything`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply(
                    """<root status_code="400" status_message="An app is already running on this host">
                       <resume>1</resume></root>""",
                )
            every { gateway.getHttps(match { it.contains("/resume") }, any()) } returns
                reply("""<root status_code="200"><sessionUrl0>rtsp://10.0.0.5:48010</sessionUrl0></root>""")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            bind("a")

            verify(exactly = 1) { gateway.getHttps(match { it.contains("/resume") }, any()) }
            verify(exactly = 0) { gateway.getHttp(match { it.contains("/pair") }, any()) }
            assertTrue(seen.none { it is MoonlightConnectionEvent.AppAlreadyRunning })
            assertTrue(seen.none { it is MoonlightConnectionEvent.RejoinRefused })
            collector.cancel()
        }
}
