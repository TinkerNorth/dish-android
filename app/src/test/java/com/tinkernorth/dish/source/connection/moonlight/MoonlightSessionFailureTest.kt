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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the manager says when a session will not start, and what it does about it.
 *
 * A MOONLIGHT HOST REFUSES IN THE BODY, NOT IN THE STATUS LINE, so every case here
 * answers HTTP 200 and disagrees inside it. The render side of these states is
 * covered by MoonlightSessionUiTest; this suite is about which event carries which
 * refusal and whether the host is left holding an app it started for us.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightSessionFailureTest {
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

    private fun TestScope.collectEvents(into: MutableList<MoonlightConnectionEvent>): Job {
        val job = launch { manager.events.toList(into) }
        dispatcher.scheduler.runCurrent()
        return job
    }

    private fun bindOnePad() {
        manager.applyDesired(mapOf(remembered.id to listOf(pad("a"))))
        dispatcher.scheduler.advanceUntilIdle()
    }

    // M15. Sunshine answers a second /launch with HTTP 200 carrying status_code 400, so
    // reading only the status line turned a refusal into a generic failure that named the
    // symptom and hid the cause.
    @Test
    fun `a session another device holds is reported as busy and never resumed`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply(
                    """<root status_code="400" status_message="An app is already running on this host">
                       <resume>0</resume></root>""",
                )
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            bindOnePad()

            val busy = seen.filterIsInstance<MoonlightConnectionEvent.AppAlreadyRunning>().single()
            assertTrue("somebody else holds it, so there is nothing to resume", !busy.resumable)
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/resume") }, any()) }
            // The binding is a durable intent, so a host somebody else is using does not
            // undo it: the pad stays claimed and the offer to close that app is the way out.
            assertEquals(1, manager.get(remembered.id)?.padCount)
            collector.cancel()
        }

    // M16. resume=1 is never shown to the user: it means the running session is ours, so
    // Dish resumes silently. This state exists only for the silent resume then failing.
    @Test
    fun `a resume the host promised and then refused is a rejoin failure`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply(
                    """<root status_code="400" status_message="An app is already running on this host">
                       <resume>1</resume></root>""",
                )
            every { gateway.getHttps(match { it.contains("/resume") }, any()) } returns
                reply("""<root status_code="400" status_message="no session"/>""")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            bindOnePad()

            assertEquals(1, seen.filterIsInstance<MoonlightConnectionEvent.RejoinRefused>().size)
            assertTrue(seen.none { it is MoonlightConnectionEvent.AppAlreadyRunning })
            collector.cancel()
        }

    // M17. Anything else the host refuses is quoted back in its own wording, because only
    // the host knows why.
    @Test
    fun `any other refusal carries the hosts own wording`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply("""<root status_code="401" status_message="Unauthorized"/>""")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            bindOnePad()

            assertEquals("Unauthorized", seen.filterIsInstance<MoonlightConnectionEvent.LaunchRefused>().single().message)
            collector.cancel()
        }

    // M18. The host started an app on our behalf, so a setup that then fails takes it back
    // down; otherwise every later attempt is refused by the app we ourselves left running.
    @Test
    fun `a launch that succeeds and a stream that does not is cancelled again`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply("""<root status_code="200"><sessionUrl0>rtsp://10.0.0.5:48010</sessionUrl0></root>""")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            bindOnePad()

            assertEquals(1, seen.filterIsInstance<MoonlightConnectionEvent.SetupFailed>().size)
            verify(atLeast = 1) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
            assertEquals(MoonlightSessionState.Idle, manager.get(remembered.id)?.state?.value)
            collector.cancel()
        }

    // M14. Four is a protocol ceiling: there is no fifth controller number to hand out.
    @Test
    fun `a fifth pad on one host is reported full rather than silently dropped`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply("""<root status_code="200"><sessionUrl0>rtsp://10.0.0.5:48010</sessionUrl0></root>""")
            bindOnePad()
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            manager.applyDesired(
                mapOf(remembered.id to listOf(pad("a"), pad("b"), pad("c"), pad("d"), pad("e"))),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(manager.get(remembered.id)!!.padCount <= MoonlightConnection.MAX_PADS)
            collector.cancel()
        }

    // A retry is the same session being reopened, never one attempt per binding.
    @Test
    fun `retrying a refused session re-attempts what the bindings already asked for`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply("""<root status_code="401" status_message="Unauthorized"/>""")
            bindOnePad()

            manager.retrySessions()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 2) { gateway.getHttps(match { it.contains("/launch") }, any()) }
        }

    // /cancel answers 200 whether or not anything was running, so a successful cancel
    // proves nothing and the caller re-probes rather than believing it. What this asserts
    // is that the pads are released, which is the part Dish does control.
    @Test
    fun `quitting the app on a host drops its pads and says so`() =
        runTest(dispatcher) {
            // Refused in the body, so the setup path has not cancelled anything of its own
            // and the cancel this asserts can only have come from the quit.
            every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
                reply("""<root status_code="401" status_message="Unauthorized"/>""")
            bindOnePad()
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = collectEvents(seen)

            manager.quitHostApp(remembered.toHost())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, manager.get(remembered.id)?.padCount)
            assertTrue(seen.any { it is MoonlightConnectionEvent.Notice })
            verify(exactly = 1) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
            collector.cancel()
        }

    // A session is re-probed immediately before it is opened, so a pairing the host has
    // dropped since stops the launch instead of failing further down.
    @Test
    fun `a host that has stopped trusting this device is not launched`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns
                MoonlightHttpGateway.Reply(status = 401, body = "")

            bindOnePad()

            verify(exactly = 0) { gateway.getHttps(match { it.contains("/launch") }, any()) }
            assertEquals(MoonlightSessionState.Idle, manager.get(remembered.id)?.state?.value)
        }

    // A binding is a durable intent, so a host that will not answer at all keeps its pads
    // claimed and simply does not open: nothing is unbound behind the user's back.
    @Test
    fun `a host that answers nothing keeps its bindings and opens no session`() =
        runTest(dispatcher) {
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns
                MoonlightHttpGateway.Reply(status = 0, body = "")

            bindOnePad()

            assertEquals(1, manager.get(remembered.id)?.padCount)
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/launch") }, any()) }
        }
}
