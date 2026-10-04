// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.model.capabilitySetOf
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightHttpGateway
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A bound pad leaving the registry, end to end through the real session manager. The binding
 * observer unbinds the departed pad's slot on its own collector, so the session controller sees
 * the device go before it sees the binding go. Whatever it asks for in between reaches the host.
 *
 * The controller and the manager run on two schedulers so a converge the controller asks for can
 * be queued before the session is handed its control stream, the way the real stream is up
 * before the pad leaves; the receive pump that follows cannot run ahead of that converge.
 */
class MoonlightPadDepartureTest {
    private val controllerTime = TestCoroutineScheduler()
    private val managerTime = TestCoroutineScheduler()
    private val managerDispatcher = StandardTestDispatcher(managerTime)

    private val bindings = MutableStateFlow(mapOf(PAD_SLOT to HOST_ID))
    private val connections =
        MutableStateFlow(
            listOf(
                ConnectionSummary(
                    id = HOST_ID,
                    kind = ConnectionKind.MOONLIGHT,
                    label = "PC",
                    detail = "",
                    live = LinkState.Saved,
                    boundSlotIds = emptyList(),
                ),
            ),
        )
    private val devices = MutableStateFlow(mapOf(PAD_ID to PhysicalGamepadRegistry.Device(PAD_ID, "Pad", hasGyro = true)))

    private val remembered =
        RememberedMoonlight(
            id = HOST_ID,
            name = "PC",
            address = "10.0.0.5",
            uniqueId = "abc",
            lastAppId = "1",
            lastAppName = "Desktop",
        )

    private val serverInfo =
        """<root status_code="200"><hostname>PC</hostname><uniqueid>abc</uniqueid>
           <PairStatus>1</PairStatus><currentgame>0</currentgame></root>"""

    // Refused in the body, so the launch never reaches a socket: the pad stays held with no
    // stream, and the stream is handed over by hand below.
    private val refusedLaunch = """<root status_code="401" status_message="Unauthorized"/>"""

    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var manager: MoonlightConnectionManager

    // The composer's answer for the candidate the controller asks it about: a pad with a gyro,
    // and no input at all once the registry no longer has the device.
    private val gyroPad =
        capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE, Feature.MOTION).let { layer ->
            SlotCapabilities(layer, layer, layer, layer, layer, CapabilitySet.EMPTY)
        }

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "0123456789abcdef"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = mockk(relaxed = true)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(serverInfo)
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns reply(serverInfo)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns
            reply("""<root status_code="200"><App><AppTitle>Desktop</AppTitle><ID>1</ID></App></root>""")
        every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns reply(refusedLaunch)
        every { gateway.getHttps(match { it.contains("/cancel") }, any()) } returns
            reply("""<root status_code="200"><cancel>1</cancel></root>""")

        val store = mockk<RememberedMoonlightRepository>(relaxed = true)
        every { store.get(HOST_ID) } returns remembered
        every { store.entries } returns MutableStateFlow(listOf(remembered))

        manager =
            MoonlightConnectionManager(
                context = context,
                scope = TestScope(managerDispatcher),
                ioDispatcher = managerDispatcher,
                discovery = mockk(relaxed = true),
                gateway = gateway,
                identity = mockk<MoonlightIdentity>(relaxed = true),
                store = store,
                bindings = mockk(relaxed = true),
            )

        val hub = mockk<ConnectionCoordinator>(relaxed = true)
        every { hub.bindings } returns bindings
        every { hub.connections } returns connections
        every { hub.satTypes } returns MutableStateFlow(emptyMap())
        val capabilities = mockk<CapabilityComposer>(relaxed = true)
        every { capabilities.capabilityForCandidate(any(), any(), any(), any(), any()) } answers {
            if (devices.value.containsKey(firstArg<String>().toInt())) gyroPad else SlotCapabilities.NONE
        }
        val registry = mockk<PhysicalGamepadRegistry> { every { devices } returns this@MoonlightPadDepartureTest.devices }
        MoonlightSessionController(
            context = context,
            hub = hub,
            moonlight = manager,
            capabilities = capabilities,
            registry = registry,
            rumble = mockk(relaxed = true),
            feedback = mockk(relaxed = true),
            scope = TestScope(StandardTestDispatcher(controllerTime)),
        ).onStart(mockk<LifecycleOwner>(relaxed = true))
        settle()
    }

    private fun settle() {
        controllerTime.advanceUntilIdle()
        managerTime.advanceUntilIdle()
    }

    private fun session(): MoonlightConnection = manager.get(HOST_ID)!!

    private fun MockKMatcherScope.launches() = gateway.getHttps(match { it.contains("/launch") }, any())

    private fun MockKMatcherScope.cancels() = gateway.getHttps(match { it.contains("/cancel") }, any())

    @Test
    fun `the gyro pad is held as PlayStation before it leaves`() {
        assertEquals(PLAYSTATION, session().padFor(PAD_SLOT)?.emulatedType)
        verify(exactly = 1) { launches() }
    }

    // Case A. A dropped session is only released when its last pad goes; reopening it on the
    // way would launch or resume the game for the unbind to close straight after.
    @Test
    fun `a pad leaving a dropped session releases it without reopening or closing the game`() {
        session().markDropped()

        devices.value = emptyMap()
        settle()
        bindings.value = emptyMap()
        settle()

        verify(exactly = 1) { launches() }
        verify(exactly = 0) { gateway.getHttps(match { it.contains("/resume") }, any()) }
        verify(exactly = 0) { cancels() }
        assertEquals(0, session().padCount)
        assertEquals(MoonlightSessionState.Idle, session().state.value)
    }

    // Case B. The host would see the PlayStation pad unplugged and an Xbox pad plugged in under
    // its number just before the pad is unplugged for good.
    @Test
    fun `a PlayStation pad leaving a live session is never replugged as another pad`() {
        devices.value = emptyMap()
        controllerTime.advanceUntilIdle()
        val stream = mockk<MoonlightControlSession>(relaxed = true)
        session().markLive(stream, remembered.lastAppId, remembered.lastAppName)
        managerTime.advanceUntilIdle()

        verify(exactly = 0) { stream.sendControllerReplug(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { stream.sendControllerArrival(any(), any(), any(), any()) }
        verify(exactly = 1) { stream.sendControllerArrival(0, PLAYSTATION, any(), any()) }
    }

    private companion object {
        const val HOST_ID = "moonlight:10.0.0.5"
        const val PAD_ID = 1
        const val PAD_SLOT = "1"
    }
}
