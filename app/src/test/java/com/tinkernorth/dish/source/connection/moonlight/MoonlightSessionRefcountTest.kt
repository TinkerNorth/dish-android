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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The session belongs to the bindings pointing at the host, not to any one of them:
// two bindings mean one launch, and the app is only closed when a session came up.
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightSessionRefcountTest {
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
           <PairStatus>1</PairStatus><currentgame>0</currentgame><state>SUNSHINE_SERVER_FREE</state></root>"""

    private val appList =
        """<root status_code="200"><App><AppTitle>Desktop</AppTitle><ID>1</ID></App></root>"""

    private val refusedLaunch =
        """<root status_code="400" status_message="Unauthorized"></root>"""

    private fun pad(slotId: String) =
        MoonlightPadRequest(
            slotId = slotId,
            emulatedType = MoonlightEmulatedType.XBOX,
            capabilities = 0x03,
            supportedButtons = 0xFFFF,
        )

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "0123456789abcdef"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = mockk()
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

    @Test
    fun `two bindings on one host launch one session, not two`() =
        runTest(dispatcher) {
            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"), pad("b"))))
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { gateway.getHttps(match { it.contains("/launch") }, any()) }
            assertEquals(2, manager.get(remembered.id)?.padCount)
            assertEquals(
                setOf(0, 1),
                manager
                    .get(remembered.id)
                    ?.pads
                    ?.value
                    ?.values
                    ?.map { it.number }
                    ?.toSet(),
            )
        }

    @Test
    fun `a third binding joins the same host without a second launch`() =
        runTest(dispatcher) {
            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"), pad("b"))))
            dispatcher.scheduler.advanceUntilIdle()
            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"), pad("b"), pad("c"))))
            dispatcher.scheduler.advanceUntilIdle()

            // The launch was refused, so the retry attempt is the same one session being
            // reopened for the same host, never one attempt per binding.
            verify(exactly = 2) { gateway.getHttps(match { it.contains("/launch") }, any()) }
            assertEquals(3, manager.get(remembered.id)?.padCount)
        }

    @Test
    fun `dropping one of two bindings frees its number and cancels nothing`() =
        runTest(dispatcher) {
            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"), pad("b"))))
            dispatcher.scheduler.advanceUntilIdle()

            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"))))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, manager.get(remembered.id)?.padCount)
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
        }

    @Test
    fun `the last unbind drops every pad, and a session that never came up is not cancelled`() =
        runTest(dispatcher) {
            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"), pad("b"))))
            dispatcher.scheduler.advanceUntilIdle()

            manager.applyDesired(emptyMap())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, manager.get(remembered.id)?.padCount)
            assertEquals(MoonlightSessionState.Idle, manager.get(remembered.id)?.state?.value)
            assertEquals(emptySet<String>(), manager.sessionHostIds.value)
            verify(exactly = 0) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
        }

    @Test
    fun `the session is re-probed immediately before it is opened`() =
        runTest(dispatcher) {
            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"))))
            dispatcher.scheduler.advanceUntilIdle()

            verify(atLeast = 1) { gateway.getHttp(match { it.contains("/serverinfo") }, any()) }
            verify(atLeast = 1) { gateway.getHttps(match { it.contains("/serverinfo") }, any()) }
        }

    @Test
    fun `a host that answers under a new identity is reported replaced instead of launched`() =
        runTest(dispatcher) {
            val replaced =
                """<root status_code="200"><uniqueid>zzz</uniqueid><PairStatus>1</PairStatus>
                   <currentgame>0</currentgame></root>"""
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(replaced)

            manager.applyDesired(mapOf(remembered.id to listOf(pad("a"))))
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { gateway.getHttps(match { it.contains("/launch") }, any()) }
            assertEquals(MoonlightSessionState.Idle, manager.get(remembered.id)?.state?.value)
        }
}
