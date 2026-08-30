// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightPadRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Bindings in, sessions out: which pads each Moonlight host is asked to carry, and the
// foreground service that keeps the process able to hold them up with the screen off.
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightSessionControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val connections = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val satTypes = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())

    private lateinit var context: Context
    private lateinit var hub: ConnectionCoordinator
    private lateinit var moonlight: MoonlightConnectionManager
    private lateinit var capabilities: CapabilityComposer
    private lateinit var owner: LifecycleOwner

    private val padCaps =
        SlotCapabilities(
            controller = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            transport = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            type = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            host = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            userEnabled = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            runtimeDown = CapabilitySet.EMPTY,
        )

    private val motionCaps =
        padCaps.copy(
            controller = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE, Feature.MOTION),
        )

    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.MOONLIGHT,
    ) = ConnectionSummary(id = id, kind = kind, label = id, detail = "", live = LinkState.Saved, boundSlotIds = emptyList())

    private val rumble: RumbleRouter = mockk(relaxed = true)

    private fun controller() =
        MoonlightSessionController(
            context = context,
            hub = hub,
            moonlight = moonlight,
            capabilities = capabilities,
            rumble = rumble,
            scope = TestScope(dispatcher),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = mockk(relaxed = true)
        hub = mockk(relaxed = true)
        moonlight = mockk(relaxed = true)
        // A relaxed mock cannot stand in for a StateFlow's collect, which never
        // returns; the feedback wiring collects it, so it has to be a real flow.
        every { moonlight.connections } returns MutableStateFlow(emptyMap())
        capabilities = mockk(relaxed = true)
        owner = mockk(relaxed = true)
        every { hub.bindings } returns bindings
        every { hub.connections } returns connections
        every { hub.satTypes } returns satTypes
        every { capabilities.capabilityForCandidate(any(), any(), any(), any()) } returns padCaps
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `only Moonlight bindings become desired pads`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"), summary("sat:a", ConnectionKind.SATELLITE))
            bindings.value = mapOf("1" to "moonlight:pc", "2" to "sat:a")
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(setOf("moonlight:pc"), desired.captured.keys)
            assertEquals(listOf("1"), desired.captured.getValue("moonlight:pc").map { it.slotId })
        }

    @Test
    fun `every binding on a host is one entry in that hosts pad list`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc", "2" to "moonlight:pc")
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(setOf("1", "2"), desired.captured.getValue("moonlight:pc").mapTo(mutableSetOf()) { it.slotId })
        }

    @Test
    fun `a binding with no stored type asks for Auto, resolved client-side before the wire`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            val pad = desired.captured.getValue("moonlight:pc").single()
            assertEquals(MoonlightEmulatedType.XBOX, pad.emulatedType)
            assertEquals(0x03, pad.capabilities)
            assertEquals(0xFFFF, pad.supportedButtons)
        }

    @Test
    fun `Auto becomes PlayStation when the bound input reports motion`() =
        runTest(dispatcher) {
            every { capabilities.capabilityForCandidate(any(), any(), any(), any()) } returns motionCaps
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(
                MoonlightEmulatedType.PLAYSTATION,
                desired.captured
                    .getValue("moonlight:pc")
                    .single()
                    .emulatedType,
            )
        }

    @Test
    fun `a stored 0 from an older build is read back as Auto, not as unknown`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            satTypes.value = mapOf(("moonlight:pc" to "1") to 0)
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(
                MoonlightEmulatedType.XBOX,
                desired.captured
                    .getValue("moonlight:pc")
                    .single()
                    .emulatedType,
            )
        }

    @Test
    fun `an explicit Nintendo pick reaches the wire as Nintendo`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            satTypes.value = mapOf(("moonlight:pc" to "1") to MoonlightEmulatedType.NINTENDO)
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(
                MoonlightEmulatedType.NINTENDO,
                desired.captured
                    .getValue("moonlight:pc")
                    .single()
                    .emulatedType,
            )
        }

    @Test
    fun `the first binding on a host starts the foreground service`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { context.startService(any()) }
        }

    @Test
    fun `a second binding on the same host does not start a second service`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val controller = controller()
            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            bindings.value = mapOf("1" to "moonlight:pc", "2" to "moonlight:pc")
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { context.startService(any()) }
            verify(exactly = 0) { context.stopService(any()) }
        }

    @Test
    fun `the last unbind stops the foreground service`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val controller = controller()
            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            bindings.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { context.stopService(any()) }
        }

    @Test
    fun `no Moonlight binding means no service at all`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("sat:a", ConnectionKind.SATELLITE))
            bindings.value = mapOf("1" to "sat:a")

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { context.startService(any()) }
            verify(exactly = 0) { context.startForegroundService(any()) }
        }

    @Test
    fun `the service goes up before the session is converged and down after it`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val controller = controller()
            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            bindings.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()

            verify {
                context.startService(any())
                moonlight.applyDesired(match { pads -> pads.values.any { it.isNotEmpty() } })
                moonlight.applyDesired(match { pads -> pads.values.none { it.isNotEmpty() } })
                context.stopService(any())
            }
        }

    @Test
    fun `host rumble reaches the pad bound to that controller number`() =
        runTest(dispatcher) {
            // The host names a pad by controller number; the connection is what knows
            // which slot took that number, and the router is what knows the slot's
            // actuator. Nothing here needed a satellite session handle.
            val conn =
                MoonlightConnection(
                    id = "moonlight:uid:abc",
                    host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
                    scope = TestScope(dispatcher),
                    ioDispatcher = dispatcher,
                )
            conn.acquirePad("pad-a", MoonlightEmulatedType.XBOX, 0x03, 0xFFFF)
            conn.acquirePad("pad-b", MoonlightEmulatedType.XBOX, 0x03, 0xFFFF)
            every { moonlight.connections } returns MutableStateFlow(mapOf(conn.id to conn))
            controller()
            dispatcher.scheduler.advanceUntilIdle()

            conn.dispatchFeedback(MoonlightEvent.Rumble(controllerNumber = 1, lowFrequency = 65535, highFrequency = 1000))

            verify(exactly = 1) { rumble.dispatchToSlot("pad-b", 65535, 1000, any()) }
            verify(exactly = 0) { rumble.dispatchToSlot("pad-a", any(), any(), any()) }
        }
}
