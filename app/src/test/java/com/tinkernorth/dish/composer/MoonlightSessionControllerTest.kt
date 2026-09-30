// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.model.capabilitySetOf
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightPadRequest
import com.tinkernorth.dish.source.lights.LightSource
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
    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())

    private lateinit var context: Context
    private lateinit var hub: ConnectionCoordinator
    private lateinit var moonlight: MoonlightConnectionManager
    private lateinit var capabilities: CapabilityComposer
    private lateinit var owner: LifecycleOwner

    private val padCaps =
        SlotCapabilities(
            controller = capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            transport = capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            type = capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            host = capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            userEnabled = capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE),
            runtimeDown = CapabilitySet.EMPTY,
        )

    private val motionCaps =
        padCaps.copy(
            controller = capabilitySetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE, Feature.MOTION),
        )

    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.MOONLIGHT,
    ) = ConnectionSummary(id = id, kind = kind, label = id, detail = "", live = LinkState.Saved, boundSlotIds = emptyList())

    private val rumble: RumbleRouter = mockk(relaxed = true)

    private val feedback: FeedbackRouter = mockk(relaxed = true)

    private val registry: PhysicalGamepadRegistry = mockk { every { devices } returns this@MoonlightSessionControllerTest.devices }

    private fun controller() =
        MoonlightSessionController(
            context = context,
            hub = hub,
            moonlight = moonlight,
            capabilities = capabilities,
            registry = registry,
            rumble = rumble,
            feedback = feedback,
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
        every { capabilities.capabilityForCandidate(any(), any(), any(), any(), any()) } returns padCaps
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
            assertEquals(XBOX, pad.emulatedType)
            assertEquals(0x03, pad.capabilities)
            assertEquals(0xFFFF, pad.supportedButtons)
        }

    @Test
    fun `Auto becomes PlayStation when the bound input reports motion`() =
        runTest(dispatcher) {
            every { capabilities.capabilityForCandidate(any(), any(), any(), any(), any()) } returns motionCaps
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(
                PLAYSTATION,
                desired.captured
                    .getValue("moonlight:pc")
                    .single()
                    .emulatedType,
            )
        }

    // Android can enumerate a pad's motion sensor after the pad itself, so a pad bound on Auto
    // first resolves without a gyro; the pads are asked for again once the gyro shows up, so a
    // pad the session acquires from then on is announced as the type its motion resolves to.
    @Test
    fun `a gyro that enumerates after the binding re-resolves Auto to PlayStation`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad"))
            val desired = mutableListOf<Map<String, List<MoonlightPadRequest>>>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                XBOX,
                desired
                    .last()
                    .getValue("moonlight:pc")
                    .single()
                    .emulatedType,
            )

            every { capabilities.capabilityForCandidate(any(), any(), any(), any(), any()) } returns motionCaps
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad", hasGyro = true))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                PLAYSTATION,
                desired
                    .last()
                    .getValue("moonlight:pc")
                    .single()
                    .emulatedType,
            )
        }

    @Test
    fun `a device change that resolves the same pads asks for nothing again`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad"))

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad", disconnectingTimeLeftSec = 3))
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { moonlight.applyDesired(any()) }
        }

    // The composer answers a candidate for a slot with no device behind it with no input at
    // all (its controller layer is empty); this mirrors that, so a pad can leave the registry.
    private fun capabilitiesFollowDevices() {
        every { capabilities.capabilityForCandidate(any(), any(), any(), any(), any()) } answers {
            val device = firstArg<String>().toIntOrNull()?.let { devices.value[it] }
            when {
                device == null -> SlotCapabilities.NONE
                device.hasGyro -> motionCaps
                else -> padCaps
            }
        }
    }

    private fun MutableList<MoonlightDesiredPads>.lastPadOn(hostId: String): MoonlightPadRequest? = last()[hostId]?.singleOrNull()

    // A bound pad leaving the registry is followed by the binding observer's unbind, and the
    // two collectors run independently: re-deriving the pad from its absent device announced a
    // caps-0 Xbox pad in between, which replugged a held PlayStation pad or reopened a dropped
    // session only for the unbind to close it again. An absent device leaves its request as it was.
    @Test
    fun `a bound pad leaving the registry asks the host for nothing new`() =
        runTest(dispatcher) {
            capabilitiesFollowDevices()
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad", hasGyro = true))
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit
            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(PLAYSTATION, desired.lastPadOn("moonlight:pc")?.emulatedType)

            devices.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, desired.size)
        }

    @Test
    fun `the unbind that follows a departed pad is what empties the host`() =
        runTest(dispatcher) {
            capabilitiesFollowDevices()
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad"))
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit
            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            devices.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()
            bindings.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, desired.size)
            assertEquals(emptyMap<String, List<MoonlightPadRequest>>(), desired.last())
        }

    // Nothing was ever derived for it, so there is no request to leave as it was, and a pad
    // resolved from no device at all would be the caps-0 Xbox pad.
    @Test
    fun `a binding whose device the registry has never shown asks for no pad`() =
        runTest(dispatcher) {
            capabilitiesFollowDevices()
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc", "2" to "moonlight:pc")
            devices.value = mapOf(2 to PhysicalGamepadRegistry.Device(2, "Pad"))
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("2"), desired.last().getValue("moonlight:pc").map { it.slotId })
        }

    @Test
    fun `a host whose only binding has no device is not asked for a session`() =
        runTest(dispatcher) {
            capabilitiesFollowDevices()
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptyMap<String, List<MoonlightPadRequest>>(), desired.last())
            verify(exactly = 0) { context.startService(any()) }
        }

    // The request left as it was is the one the pad had on that host: a pad moved to another
    // host while its device is away has nothing there to leave as it was.
    @Test
    fun `a departed pad moved to another host asks that host for nothing`() =
        runTest(dispatcher) {
            capabilitiesFollowDevices()
            connections.value = listOf(summary("moonlight:pc"), summary("moonlight:den"))
            bindings.value = mapOf("1" to "moonlight:pc")
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad"))
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit
            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            devices.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()
            bindings.value = mapOf("1" to "moonlight:den")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptyMap<String, List<MoonlightPadRequest>>(), desired.last())
        }

    // The type is resolved from one read of the slot and the bits from a second, and the device
    // can leave or arrive between them: a pad is derived only when both reads saw it.
    @Test
    fun `a pad that leaves between the type read and the bits read asks for no pad`() =
        runTest(dispatcher) {
            every { capabilities.capabilityForCandidate(any(), XBOX, any(), any(), any()) } returns padCaps
            every { capabilities.capabilityForCandidate(any(), PLAYSTATION, any(), any(), any()) } returns SlotCapabilities.NONE
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            satTypes.value = mapOf(("moonlight:pc" to "1") to PLAYSTATION)
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptyMap<String, List<MoonlightPadRequest>>(), desired.last())
        }

    @Test
    fun `a pad that arrives between the type read and the bits read asks for no pad`() =
        runTest(dispatcher) {
            every { capabilities.capabilityForCandidate(any(), XBOX, any(), any(), any()) } returns SlotCapabilities.NONE
            every { capabilities.capabilityForCandidate(any(), PLAYSTATION, any(), any(), any()) } returns motionCaps
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            satTypes.value = mapOf(("moonlight:pc" to "1") to PLAYSTATION)
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptyMap<String, List<MoonlightPadRequest>>(), desired.last())
        }

    // Only the device's absence is held back: a device that is present is derived again, so
    // the late gyro above still reaches a pad the session acquires afterwards.
    @Test
    fun `a pad whose device comes back is derived from it again`() =
        runTest(dispatcher) {
            capabilitiesFollowDevices()
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad"))
            val desired = mutableListOf<MoonlightDesiredPads>()
            every { moonlight.applyDesired(capture(desired)) } returns Unit
            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            devices.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()
            devices.value = mapOf(1 to PhysicalGamepadRegistry.Device(1, "Pad", hasGyro = true))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(PLAYSTATION, desired.lastPadOn("moonlight:pc")?.emulatedType)
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
                XBOX,
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
            satTypes.value = mapOf(("moonlight:pc" to "1") to NINTENDO)
            val desired = slot<Map<String, List<MoonlightPadRequest>>>()

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.applyDesired(capture(desired)) }
            assertEquals(
                NINTENDO,
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
            conn.acquirePad("pad-a", XBOX, 0x03, 0xFFFF)
            conn.acquirePad("pad-b", XBOX, 0x03, 0xFFFF)
            every { moonlight.connections } returns MutableStateFlow(mapOf(conn.id to conn))
            controller()
            dispatcher.scheduler.advanceUntilIdle()

            conn.dispatchFeedback(MoonlightEvent.Rumble(controllerNumber = 1, lowFrequency = 65535, highFrequency = 1000))

            verify(exactly = 1) { rumble.dispatchToSlot("pad-b", 65535, 1000, any()) }
            verify(exactly = 0) { rumble.dispatchToSlot("pad-a", any(), any(), any()) }
        }

    @Test
    fun `host trigger rumble and LED reach the feedback router for the right slot`() =
        runTest(dispatcher) {
            val conn =
                MoonlightConnection(
                    id = "moonlight:uid:abc",
                    host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
                    scope = TestScope(dispatcher),
                    ioDispatcher = dispatcher,
                )
            conn.acquirePad("pad-a", XBOX, 0x07, 0xFFFF)
            conn.acquirePad("pad-b", XBOX, 0x07, 0xFFFF)
            every { moonlight.connections } returns MutableStateFlow(mapOf(conn.id to conn))
            controller()
            dispatcher.scheduler.advanceUntilIdle()

            conn.dispatchFeedback(MoonlightEvent.RumbleTriggers(controllerNumber = 0, left = 1234, right = 4321))
            conn.dispatchFeedback(MoonlightEvent.RgbLed(controllerNumber = 1, red = 10, green = 20, blue = 30))

            verify(exactly = 1) { feedback.dispatchTriggerRumbleToSlot("pad-a", 1234, 4321) }
            verify(exactly = 0) { feedback.dispatchTriggerRumbleToSlot("pad-b", any(), any()) }
            verify(exactly = 1) { feedback.dispatchLightbarToSlot("pad-b", LightSource(conn.id, 1), 10, 20, 30) }
            verify(exactly = 0) { feedback.dispatchLightbarToSlot("pad-a", any(), any(), any(), any()) }
        }

    @Test
    fun `motion request is absorbed by the connection gate, not the routers`() =
        runTest(dispatcher) {
            val conn =
                MoonlightConnection(
                    id = "moonlight:uid:abc",
                    host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
                    scope = TestScope(dispatcher),
                    ioDispatcher = dispatcher,
                )
            conn.acquirePad("pad-a", PLAYSTATION, 0x37, 0xFFFF)
            every { moonlight.connections } returns MutableStateFlow(mapOf(conn.id to conn))
            controller()
            dispatcher.scheduler.advanceUntilIdle()

            conn.dispatchFeedback(MoonlightEvent.MotionRequest(controllerNumber = 0, reportRateHz = 100, motionType = 2))

            verify(exactly = 0) { rumble.dispatchToSlot(any(), any(), any(), any()) }
            verify(exactly = 0) { feedback.dispatchTriggerRumbleToSlot(any(), any(), any()) }
            // The connection recorded the request: the sink now wants motion for the slot.
            org.junit.Assert.assertTrue(conn.motionWanted("pad-a"))
            conn.dispatchFeedback(MoonlightEvent.MotionRequest(controllerNumber = 0, reportRateHz = 0, motionType = 2))
            org.junit.Assert.assertFalse(conn.motionWanted("pad-a"))
        }

    private fun liveConnection(): MoonlightConnection =
        MoonlightConnection(
            id = "moonlight:uid:abc",
            host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
        )

    @Test
    fun `feedback for an unassigned controller number is dropped`() =
        runTest(dispatcher) {
            val conn = liveConnection()
            conn.acquirePad("pad-a", XBOX, 0x03, 0xFFFF)
            every { moonlight.connections } returns MutableStateFlow(mapOf(conn.id to conn))
            controller()
            dispatcher.scheduler.advanceUntilIdle()

            conn.dispatchFeedback(MoonlightEvent.Rumble(controllerNumber = 3, lowFrequency = 65535, highFrequency = 1000))

            verify(exactly = 0) { rumble.dispatchToSlot(any(), any(), any(), any()) }
        }

    @Test
    fun `termination and unknown events reach no router`() =
        runTest(dispatcher) {
            val conn = liveConnection()
            conn.acquirePad("pad-a", XBOX, 0x07, 0xFFFF)
            every { moonlight.connections } returns MutableStateFlow(mapOf(conn.id to conn))
            controller()
            dispatcher.scheduler.advanceUntilIdle()

            conn.dispatchFeedback(MoonlightEvent.Termination(reason = 0))
            conn.dispatchFeedback(MoonlightEvent.Unknown(type = 0x0200))

            verify(exactly = 0) { rumble.dispatchToSlot(any(), any(), any(), any()) }
            verify(exactly = 0) { feedback.dispatchTriggerRumbleToSlot(any(), any(), any()) }
            verify(exactly = 0) { feedback.dispatchLightbarToSlot(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a refused service start still converges the session`() =
        runTest(dispatcher) {
            every { context.startService(any()) } throws IllegalStateException("background start refused")
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { moonlight.applyDesired(match { pads -> pads.getValue("moonlight:pc").size == 1 }) }
        }

    @Test
    fun `a refused service start is retried on the next emission`() =
        runTest(dispatcher) {
            every { context.startService(any()) } throws IllegalStateException("background start refused") andThen null
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            bindings.value = mapOf("1" to "moonlight:pc", "2" to "moonlight:pc")
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 2) { context.startService(any()) }
        }

    @Test
    fun `a binding change after onStop still converges the session`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val controller = controller()
            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            controller.onStop(owner)
            bindings.value = emptyMap()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { moonlight.applyDesired(match { pads -> pads.values.none { it.isNotEmpty() } }) }
            verify(exactly = 1) { context.stopService(any()) }
        }

    @Test
    fun `onStart twice keeps one collector and starts one service`() =
        runTest(dispatcher) {
            connections.value = listOf(summary("moonlight:pc"))
            bindings.value = mapOf("1" to "moonlight:pc")
            val controller = controller()

            controller.onStart(owner)
            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { context.startService(any()) }
        }

    // The controller's own tests run at the stub's SDK level of 0, so they only ever take the
    // plain start; the choice is pinned here at both edges of API 26.
    @Test
    fun `from API 26 the session service starts as a foreground service`() {
        val intent: Intent = mockk()

        startSessionService(context, intent, Build.VERSION_CODES.O)

        verify(exactly = 1) { context.startForegroundService(intent) }
        verify(exactly = 0) { context.startService(any()) }
    }

    @Test
    fun `below API 26 the session service starts as a plain service`() {
        val intent: Intent = mockk()

        startSessionService(context, intent, Build.VERSION_CODES.N_MR1)

        verify(exactly = 1) { context.startService(intent) }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }
}
