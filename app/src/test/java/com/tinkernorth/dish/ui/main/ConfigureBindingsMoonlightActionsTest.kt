// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.InputFunctions
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionEvent
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightError
import com.tinkernorth.dish.source.connection.moonlight.MoonlightProbe
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SlotToggleStores
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import com.tinkernorth.dish.source.system.MicPermissionGate
import com.tinkernorth.dish.source.usb.PhysicalPadSources
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the binding screen DOES, as opposed to what it renders: that saving a binding
 * reaches the store whatever the host has just said about itself, and that the two
 * actions whose worth depends on what happens after them actually do it.
 *
 * The render side of the same states is pinned by MoonlightSessionUiTest and
 * ConfigUiStateMoonlightTest; this suite is the wiring underneath them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureBindingsMoonlightActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var hub: ConnectionCoordinator
    private lateinit var moonlight: MoonlightConnectionManager
    private lateinit var vm: ConfigureBindingsViewModel

    private val host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc")

    private val summary =
        ConnectionSummary(
            id = host.id,
            kind = ConnectionKind.MOONLIGHT,
            label = "PC",
            detail = "",
            live = LinkState.Saved,
            boundSlotIds = emptyList(),
        )

    private val connections = MutableStateFlow(listOf(summary))
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val satTypes = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
    private val events = MutableSharedFlow<MoonlightConnectionEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub = mockk(relaxed = true)
        every { hub.connections } returns connections
        every { hub.bindings } returns bindings
        every { hub.satTypes } returns satTypes
        every { hub.summary(host.id) } returns summary
        every { hub.bind(any(), any(), any()) } returns true

        moonlight = mockk(relaxed = true)
        every { moonlight.events } returns events
        every { moonlight.rememberedHost(host.id) } returns host
        every { moonlight.rememberedEmulatedType(host.id) } returns AUTO
        every { moonlight.rememberedAppId(host.id) } returns ""
        every { moonlight.rememberedAppName(host.id) } returns ""
        every { moonlight.get(host.id) } returns null
        coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = MoonlightTrustState.PAIRED)

        val capabilities = mockk<CapabilityComposer>(relaxed = true)
        every { capabilities.capabilityFor(any()) } returns SlotCapabilities.NONE
        every { capabilities.capabilityForCandidate(any(), any(), any(), any(), any()) } returns SlotCapabilities.NONE
        every { capabilities.inputFunctionsFor(any(), any()) } returns
            InputFunctions(known = true, rumble = false, gyro = false, touchpad = false)
        val registry = mockk<PhysicalGamepadRegistry>(relaxed = true)
        every { registry.devices } returns MutableStateFlow(emptyMap())
        val usb = mockk<UsbGamepadManager>(relaxed = true)
        every { usb.controllers } returns MutableStateFlow(emptyMap())
        val satellite = mockk<SatelliteConnectionManager>(relaxed = true)
        every { satellite.get(any()) } returns null

        vm =
            ConfigureBindingsViewModel(
                context = mockk<Context>(relaxed = true),
                hub = hub,
                pads = PhysicalPadSources(registry, mockk<PhysicalInputNative>(relaxed = true), usb, mockk(relaxed = true)),
                toggles =
                    SlotToggleStores(
                        motion = mockk<MotionEnabledStore>(relaxed = true),
                        rumble = mockk<RumbleEnabledStore>(relaxed = true),
                        mic = mockk<MicEnabledStore>(relaxed = true),
                        speaker = mockk<SpeakerEnabledStore>(relaxed = true),
                    ),
                micPermission = mockk<MicPermissionGate>(relaxed = true),
                capabilityComposer = capabilities,
                satellite = satellite,
                moonlight = moonlight,
                hostFacts =
                    SatelliteHostFacts(
                        features = SatelliteHostFeaturesStore(),
                        runtime = mockk(),
                        motionBackend = mockk(),
                        catalog = mockk<SatelliteCatalogRepository>(relaxed = true),
                        capabilities = mockk<SatelliteCapabilitiesRepository>(relaxed = true),
                    ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun openOn(trust: MoonlightTrustState) {
        coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = trust)
        vm.load(VIRTUAL_SLOT_ID)
        vm.setHost(host.id)
        dispatcher.scheduler.advanceUntilIdle()
    }

    // B12, B21. A binding is a durable intent: it is saved against the host the user
    // chose, not against the answer that host happened to give a second earlier. The
    // session is attempted when the controller is used, not when the binding is saved.
    @Test
    fun `a binding is saved whatever the host has just said about itself`() =
        runTest(dispatcher) {
            val states =
                listOf(
                    MoonlightTrustState.NOT_PAIRED,
                    MoonlightTrustState.UNREACHABLE,
                    MoonlightTrustState.REMEMBERED,
                    MoonlightTrustState.TRUST_LOST,
                    MoonlightTrustState.REPLACED,
                    MoonlightTrustState.PAIRED,
                )
            states.forEach { trust ->
                openOn(trust)

                assertTrue("$trust must not block Apply", vm.ui.value.canApply)
                vm.apply()
                dispatcher.scheduler.advanceUntilIdle()

                val finished = vm.applyState.value as ApplyState.Finished
                assertNull("$trust ended in ${finished.errorMessage}", finished.errorMessage)
                vm.dismissApplyResult()
            }
            verify(exactly = states.size) { hub.bind(VIRTUAL_SLOT_ID, host.id, AUTO) }
        }

    // B7. The controller number is 1-based for the reader and 0-based on the wire, so the
    // pad the host was told about as 0 is the one the card calls controller 1.
    @Test
    fun `a live session names the app and this binding's own controller number`() =
        runTest(dispatcher) {
            val conn = MoonlightConnection(host.id, host, TestScope(dispatcher), dispatcher)
            conn.acquirePad(VIRTUAL_SLOT_ID, XBOX, 0x03, 0xFFFF)
            every { moonlight.get(host.id) } returns conn
            coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = MoonlightTrustState.PAIRED)

            vm.load(VIRTUAL_SLOT_ID)
            vm.setHost(host.id)
            // Queued after the probe, so the probe reads the session the way a live one reads.
            conn.markLive(mockk(relaxed = true), "1", "Desktop")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.Live(controllerNumber = 1, appName = "Desktop"), vm.ui.value.moonlightSession)
        }

    // B16, H2. A cancel answers 200 whether or not anything was running, so its reply proves
    // nothing and the screen asks the host again, once the close request has gone out. Asked at
    // the same moment, the host could still report the app the quit was about to close.
    @Test
    fun `quitting the app asks the host to close it and re-checks the host once the request has gone out`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)

            vm.onMoonlightAction(MoonlightAction.QUIT_APP)
            dispatcher.scheduler.advanceUntilIdle()
            verify { moonlight.quitHostApp(host) }
            assertEquals(MoonlightSessionUi.Checking, vm.ui.value.moonlightSession)
            coVerify(exactly = 1) { moonlight.probe(host) }

            events.emit(MoonlightConnectionEvent.AppCloseRequested(host))
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 2) { moonlight.probe(host) }
        }

    @Test
    fun `a close request for another host does not re-check this one`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)

            events.emit(MoonlightConnectionEvent.AppCloseRequested(host.copy(address = "10.0.0.6")))
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { moonlight.probe(host) }
        }

    // B5. New code is only ever offered while a pairing is in flight, so a guard that did
    // nothing when one was live made the one button that state exists to offer unreachable.
    @Test
    fun `asking for a new code while a pairing is live starts another one`() =
        runTest(dispatcher) {
            coEvery { moonlight.pairHost(any()) } coAnswers { awaitCancellation() }
            openOn(MoonlightTrustState.NOT_PAIRED)

            vm.onMoonlightAction(MoonlightAction.PAIR)
            dispatcher.scheduler.runCurrent()
            vm.onMoonlightAction(MoonlightAction.NEW_CODE)
            dispatcher.scheduler.runCurrent()

            coVerify(exactly = 2) { moonlight.pairHost(host) }
        }

    // B5. Cancel drops the pairing job, so phase 1 is not left holding its socket for the
    // rest of the PIN window and completing a pairing nobody is watching any more.
    @Test
    fun `cancelling a pairing cancels the job behind it`() =
        runTest(dispatcher) {
            var running = 0
            coEvery { moonlight.pairHost(any()) } coAnswers {
                running++
                try {
                    awaitCancellation()
                } finally {
                    running--
                }
            }
            openOn(MoonlightTrustState.NOT_PAIRED)

            vm.onMoonlightAction(MoonlightAction.PAIR)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, running)
            vm.onMoonlightAction(MoonlightAction.CANCEL)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("the pairing has to stop when the user says so", 0, running)
        }

    // A session the manager holds for this host, in one state, carrying some pads.
    private fun session(
        state: MoonlightSessionState,
        padCount: Int,
        appName: String? = "Desktop",
    ): MoonlightConnection {
        val conn = mockk<MoonlightConnection>(relaxed = true)
        every { conn.state } returns MutableStateFlow(state)
        every { conn.padFor(any()) } returns null
        every { conn.padCount } returns padCount
        every { conn.sessionAppName } returns appName
        return conn
    }

    @Test
    fun `a live session without this slot's pad reads as joining the next number`() =
        runTest(dispatcher) {
            every { moonlight.get(host.id) } returns session(MoonlightSessionState.Live, padCount = 2)
            openOn(MoonlightTrustState.PAIRED)

            assertEquals(MoonlightSessionUi.Joining(controllerNumber = 3, appName = "Desktop"), vm.ui.value.moonlightSession)
        }

    @Test
    fun `a dropped session reads as dropped`() =
        runTest(dispatcher) {
            every { moonlight.get(host.id) } returns session(MoonlightSessionState.Dropped, padCount = 1)
            openOn(MoonlightTrustState.PAIRED)

            assertEquals(MoonlightSessionUi.Dropped, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a session the host ended reads as ended by host`() =
        runTest(dispatcher) {
            every { moonlight.get(host.id) } returns session(MoonlightSessionState.Ended, padCount = 1)
            openOn(MoonlightTrustState.PAIRED)

            assertEquals(MoonlightSessionUi.EndedByHost, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a probe that reports our own session reads as joining`() =
        runTest(dispatcher) {
            every { moonlight.rememberedAppName(host.id) } returns "Steam"
            coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = MoonlightTrustState.PAIRED, ownSession = true)
            vm.load(VIRTUAL_SLOT_ID)
            vm.setHost(host.id)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.Joining(controllerNumber = 1, appName = "Steam"), vm.ui.value.moonlightSession)
        }

    @Test
    fun `a host already carrying four pads reads full for a new slot`() =
        runTest(dispatcher) {
            every { moonlight.get(host.id) } returns session(MoonlightSessionState.Idle, padCount = MOONLIGHT_MAX_PADS)
            openOn(MoonlightTrustState.PAIRED)

            assertEquals(
                MoonlightFailure.HostFull,
                vm.ui.value.moonlight
                    ?.failure,
            )
            assertEquals(MoonlightSessionUi.HostFull, vm.ui.value.moonlightSession)
            assertFalse(vm.ui.value.canApply)
        }

    @Test
    fun `an app already running that is resumable records no failure`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)

            events.emit(MoonlightConnectionEvent.AppAlreadyRunning(host, resumable = true))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.AppsLoading, vm.ui.value.moonlightSession)

            events.emit(MoonlightConnectionEvent.AppAlreadyRunning(host, resumable = false))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.BusyOther, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a launch refusal records the host's wording`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)

            events.emit(MoonlightConnectionEvent.LaunchRefused(host, "Steam is busy"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.Refused("Steam is busy"), vm.ui.value.moonlightSession)
        }

    @Test
    fun `each refusal the host can give lands in its own state`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)

            events.emit(MoonlightConnectionEvent.RejoinRefused(host))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.ResumeFailed, vm.ui.value.moonlightSession)

            events.emit(MoonlightConnectionEvent.SetupFailed(host))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.SetupFailed, vm.ui.value.moonlightSession)

            events.emit(MoonlightConnectionEvent.HostFull(host))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.HostFull, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a pin on offer shows the pin and a pairing clears it`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.NOT_PAIRED)

            events.emit(MoonlightConnectionEvent.PairingPinReady(host, "1234"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.PairingPin("1234"), vm.ui.value.moonlightSession)

            events.emit(MoonlightConnectionEvent.PairingFailed(host, "timeout"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.PairFailed, vm.ui.value.moonlightSession)

            events.emit(MoonlightConnectionEvent.Paired(host))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.NotPaired, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a close request, an error or an ending leaves the last refusal in place`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)
            events.emit(MoonlightConnectionEvent.SetupFailed(host))
            dispatcher.scheduler.advanceUntilIdle()

            events.emit(MoonlightConnectionEvent.AppCloseRequested(host))
            events.emit(MoonlightConnectionEvent.EndedByHost(host))
            events.emit(MoonlightConnectionEvent.HostReplaced(host))
            events.emit(MoonlightConnectionEvent.Error(MoonlightError.NoAppsAvailable("PC")))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.SetupFailed, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a retry clears the last refusal and re-runs the converge`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)
            events.emit(MoonlightConnectionEvent.SetupFailed(host))
            dispatcher.scheduler.advanceUntilIdle()

            vm.onMoonlightAction(MoonlightAction.RETRY)
            dispatcher.scheduler.advanceUntilIdle()

            assertNotEquals(MoonlightSessionUi.SetupFailed, vm.ui.value.moonlightSession)
            verify { moonlight.disconnect(host.id) }
            verify { moonlight.retrySessions() }
        }

    @Test
    fun `a moonlight event leaves a satellite screen alone`() =
        runTest(dispatcher) {
            val satelliteId = "satellite:mid:xyz"
            val satellite =
                ConnectionSummary(
                    id = satelliteId,
                    kind = ConnectionKind.SATELLITE,
                    label = "Desk",
                    detail = "",
                    live = LinkState.Connected,
                    boundSlotIds = emptyList(),
                )
            connections.value = listOf(summary, satellite)
            every { hub.summary(satelliteId) } returns satellite
            vm.load(VIRTUAL_SLOT_ID)
            vm.setHost(satelliteId)
            dispatcher.scheduler.advanceUntilIdle()

            events.emit(MoonlightConnectionEvent.LaunchRefused(host, "no"))
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(vm.ui.value.moonlightSession)
            assertNull(vm.ui.value.moonlight)
        }

    @Test
    fun `a destination with no remembered host renders unreachable`() =
        runTest(dispatcher) {
            every { moonlight.rememberedHost(host.id) } returns null
            openOn(MoonlightTrustState.PAIRED)

            assertEquals(MoonlightSessionUi.Unreachable, vm.ui.value.moonlightSession)
        }

    // The card's memory (the pairing dialog, the last refusal) rides on a probe's answer, never
    // on the two placeholders the screen writes itself: Checking while it asks again, and
    // Unreachable when no host is behind the id any more.

    // Try again on a forgotten host re-renders the card; were the remembered failure drawn over
    // Unreachable, the button would be Try again once more, and it would loop forever.
    @Test
    fun `try again after a failed pairing on a host that has gone lands on unreachable`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.NOT_PAIRED)
            events.emit(MoonlightConnectionEvent.PairingFailed(host, "timeout"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.PairFailed, vm.ui.value.moonlightSession)
            every { moonlight.rememberedHost(host.id) } returns null

            vm.onMoonlightAction(MoonlightAction.TRY_AGAIN)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.Unreachable, vm.ui.value.moonlightSession)
            coVerify(exactly = 0) { moonlight.pairHost(any()) }
        }

    // Only a probe's answer carries the memory, so an event cannot bring the loop back either.
    @Test
    fun `a pairing event does not cover a host that has gone`() =
        runTest(dispatcher) {
            every { moonlight.rememberedHost(host.id) } returns null
            openOn(MoonlightTrustState.PAIRED)

            events.emit(MoonlightConnectionEvent.PairingFailed(host, "timeout"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.Unreachable, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a failed pairing waits behind checking while the host is asked again, then returns`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.NOT_PAIRED)
            events.emit(MoonlightConnectionEvent.PairingFailed(host, "timeout"))
            dispatcher.scheduler.advanceUntilIdle()
            val answer = holdTheNextProbe()

            vm.onMoonlightAction(MoonlightAction.TRY_AGAIN)
            dispatcher.scheduler.runCurrent()
            assertEquals(MoonlightSessionUi.Checking, vm.ui.value.moonlightSession)

            answer.complete(MoonlightProbe(trust = MoonlightTrustState.NOT_PAIRED))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.PairFailed, vm.ui.value.moonlightSession)
        }

    @Test
    fun `a remembered refusal waits behind checking while the host is asked again, then returns`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)
            events.emit(MoonlightConnectionEvent.SetupFailed(host))
            dispatcher.scheduler.advanceUntilIdle()
            val answer = holdTheNextProbe()

            vm.refreshMoonlight()
            dispatcher.scheduler.runCurrent()
            assertEquals(MoonlightSessionUi.Checking, vm.ui.value.moonlightSession)

            answer.complete(MoonlightProbe(trust = MoonlightTrustState.PAIRED))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(MoonlightSessionUi.SetupFailed, vm.ui.value.moonlightSession)
        }

    // The next probe suspends until the test answers it, so the placeholder can be read.
    private fun holdTheNextProbe(): CompletableDeferred<MoonlightProbe> {
        val answer = CompletableDeferred<MoonlightProbe>()
        coEvery { moonlight.probe(any()) } coAnswers { answer.await() }
        return answer
    }
}
