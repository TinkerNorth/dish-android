// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.InputFunctions
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import com.tinkernorth.dish.source.system.MicPermissionGate
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The binding screen's two audio toggles: they seed from the same per-slot stores the
 * other toggles use, persist on Apply only where the path carries them, and the mic one
 * carries the RECORD_AUDIO seam the capture wave fills in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureBindingsAudioTogglesTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val satTypesFlow = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val devicesFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())

    private lateinit var hub: ConnectionCoordinator
    private lateinit var micStore: MicEnabledStore
    private lateinit var speakerStore: SpeakerEnabledStore
    private lateinit var micPermission: MicPermissionGate
    private lateinit var composer: CapabilityComposer
    private lateinit var vm: ConfigureBindingsViewModel

    private var granted = false
    private var micSeed = false
    private var speakerSeed = true
    private var pathCaps: SlotCapabilities = capsWith(Feature.MIC, Feature.SPEAKER)

    private fun capsWith(vararg features: Feature): SlotCapabilities {
        val set = CapabilitySet(features.toSet())
        return SlotCapabilities(
            controller = set,
            transport = set,
            type = set,
            host = set,
            userEnabled = CapabilitySet.EMPTY,
            runtimeDown = CapabilitySet.EMPTY,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub = mockk(relaxed = true)
        every { hub.bindings } returns bindingsFlow
        every { hub.satTypes } returns satTypesFlow
        every { hub.connections } returns connectionsFlow
        every { hub.bind(any(), any(), any()) } returns true

        val registry: PhysicalGamepadRegistry = mockk(relaxed = true)
        every { registry.devices } returns devicesFlow
        val usb: UsbGamepadManager = mockk(relaxed = true)
        every { usb.controllers } returns MutableStateFlow(emptyMap())

        composer = mockk(relaxed = true)
        every { composer.capabilityFor(any()) } returns SlotCapabilities.NONE
        every { composer.capabilityForCandidate(any(), any(), any(), any(), any()) } answers { pathCaps }
        every { composer.inputFunctionsFor(any(), any()) } returns
            InputFunctions(known = true, rumble = false, gyro = false, touchpad = false)

        micStore = mockk(relaxed = true)
        every { micStore.isEnabled(any()) } answers { micSeed }
        speakerStore = mockk(relaxed = true)
        every { speakerStore.isEnabled(any()) } answers { speakerSeed }
        micPermission = mockk(relaxed = true)
        every { micPermission.granted } answers { granted }

        val satellite: SatelliteConnectionManager = mockk(relaxed = true)
        val conn: SatelliteConnection = mockk(relaxed = true)
        every { conn.server } returns MutableStateFlow(mockk(relaxed = true))
        // Apply waits for the slot's descriptor to come back registered before it reports
        // success, so the fake connection has to arrive already applied.
        every { conn.slots } returns
            MutableStateFlow(
                mapOf(
                    SLOT to
                        SatelliteConnection.SlotBinding(
                            controllerIndex = 0,
                            controllerType = TYPE_DUALSENSE,
                            registered = true,
                        ),
                ),
            )
        every { satellite.get(any()) } returns conn
        val catalogRepo: SatelliteCatalogRepository = mockk(relaxed = true)
        every { catalogRepo.cached(any()) } returns null
        val capabilitiesRepo: SatelliteCapabilitiesRepository = mockk(relaxed = true)
        coEvery { capabilitiesRepo.refresh(any(), any()) } returns null

        vm =
            ConfigureBindingsViewModel(
                context = mockk<Context>(relaxed = true),
                hub = hub,
                gamepadRegistry = registry,
                motionEnabledStore = mockk<MotionEnabledStore>(relaxed = true),
                rumbleEnabledStore = mockk<RumbleEnabledStore>(relaxed = true),
                micEnabledStore = micStore,
                speakerEnabledStore = speakerStore,
                micPermission = micPermission,
                capabilityComposer = composer,
                satellite = satellite,
                moonlight = mockk<MoonlightConnectionManager>(relaxed = true),
                usbGamepadManager = usb,
                catalogRepo = catalogRepo,
                capabilitiesRepo = capabilitiesRepo,
                native = mockk<PhysicalInputNative>(relaxed = true),
                hostFeaturesStore = SatelliteHostFeaturesStore(),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun open() {
        connectionsFlow.value =
            listOf(
                ConnectionSummary(
                    id = HOST,
                    kind = ConnectionKind.SATELLITE,
                    label = "PC",
                    detail = "",
                    live = LinkState.Connected,
                    boundSlotIds = emptyList(),
                ),
            )
        bindingsFlow.value = mapOf(SLOT to HOST)
        satTypesFlow.value = mapOf((HOST to SLOT) to TYPE_DUALSENSE)
        vm.load(SLOT)
        vm.setHost(HOST)
    }

    private fun currentDraft() = vm.ui.value.draft

    @Test
    fun `the draft seeds from the per-slot stores`() =
        runTest(dispatcher) {
            micSeed = true
            speakerSeed = false
            open()
            assertEquals(true, currentDraft()?.micOn)
            assertEquals(false, currentDraft()?.speakerOn)
        }

    @Test
    fun `the defaults are mic off and speaker on`() =
        runTest(dispatcher) {
            open()
            assertFalse(currentDraft()?.micOn == true)
            assertTrue(currentDraft()?.speakerOn == true)
        }

    @Test
    fun `each toggle moves only its own direction`() =
        runTest(dispatcher) {
            open()
            vm.setMic(true)
            assertTrue(currentDraft()?.micOn == true)
            assertTrue(currentDraft()?.speakerOn == true)

            vm.setSpeaker(false)
            assertTrue(currentDraft()?.micOn == true)
            assertFalse(currentDraft()?.speakerOn == true)
        }

    @Test
    fun `apply persists both toggles for a path that carries audio`() =
        runTest(dispatcher) {
            open()
            vm.setMic(true)
            vm.setSpeaker(false)
            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            verify { micStore.setEnabled(SLOT, true) }
            verify { speakerStore.setEnabled(SLOT, false) }
        }

    @Test
    fun `apply writes nothing for a path with no audio endpoints`() =
        runTest(dispatcher) {
            // Writing an "on" here would advertise a microphone the moment the user later
            // moved this slot to a host that does carry one.
            pathCaps = capsWith(Feature.GAMEPAD)
            open()
            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { micStore.setEnabled(any(), any()) }
            verify(exactly = 0) { speakerStore.setEnabled(any(), any()) }
        }

    @Test
    fun `apply persists only the direction the path carries`() =
        runTest(dispatcher) {
            pathCaps = capsWith(Feature.SPEAKER)
            open()
            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { micStore.setEnabled(any(), any()) }
            verify { speakerStore.setEnabled(SLOT, true) }
        }

    @Test
    fun `turning the mic on without the grant asks for it`() =
        runTest(dispatcher) {
            open()
            val asks = mutableListOf<Unit>()
            val job = launch { vm.micPermissionRequests.collect { asks += it } }
            dispatcher.scheduler.runCurrent()

            vm.setMic(true)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, asks.size)
            assertTrue(vm.ui.value.micNeedsPermission)
            job.cancel()
        }

    @Test
    fun `turning the mic on with the grant already held asks for nothing`() =
        runTest(dispatcher) {
            granted = true
            open()
            vm.refreshMicPermission()
            val asks = mutableListOf<Unit>()
            val job = launch { vm.micPermissionRequests.collect { asks += it } }
            dispatcher.scheduler.runCurrent()

            vm.setMic(true)
            dispatcher.scheduler.runCurrent()

            assertTrue(asks.isEmpty())
            assertFalse(vm.ui.value.micNeedsPermission)
            job.cancel()
        }

    @Test
    fun `turning the mic OFF never asks`() =
        runTest(dispatcher) {
            open()
            val asks = mutableListOf<Unit>()
            val job = launch { vm.micPermissionRequests.collect { asks += it } }
            dispatcher.scheduler.runCurrent()

            vm.setMic(false)
            dispatcher.scheduler.runCurrent()

            assertTrue(asks.isEmpty())
            assertFalse(vm.ui.value.micNeedsPermission)
            job.cancel()
        }

    @Test
    fun `the row's affordance asks again through the same seam`() =
        runTest(dispatcher) {
            open()
            vm.setMic(true)
            val asks = mutableListOf<Unit>()
            val job = launch { vm.micPermissionRequests.collect { asks += it } }
            dispatcher.scheduler.runCurrent()

            vm.requestMicPermission()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, asks.size)
            job.cancel()
        }

    @Test
    fun `a grant made in system settings clears the needs-permission state on refresh`() =
        runTest(dispatcher) {
            open()
            vm.setMic(true)
            assertTrue(vm.ui.value.micNeedsPermission)

            granted = true
            vm.refreshMicPermission()

            assertTrue(vm.ui.value.micPermissionGranted)
            assertFalse(vm.ui.value.micNeedsPermission)
            verify { micPermission.refresh() }
        }

    @Test
    fun `refreshing while already granted asks for nothing`() =
        runTest(dispatcher) {
            granted = true
            open()
            val asks = mutableListOf<Unit>()
            val job = launch { vm.micPermissionRequests.collect { asks += it } }
            dispatcher.scheduler.runCurrent()

            vm.requestMicPermission()
            dispatcher.scheduler.runCurrent()

            assertTrue(asks.isEmpty())
            job.cancel()
        }

    @Test
    fun `the audio rows follow the resolved path capabilities`() =
        runTest(dispatcher) {
            pathCaps = capsWith(Feature.MIC)
            open()
            assertTrue(vm.ui.value.micAvailable)
            assertFalse(vm.ui.value.speakerAvailable)
        }

    // ── the three states the mic row can be in ─────────────────────────────

    @Test
    fun `off, needs-permission and armed are mutually exclusive`() =
        runTest(dispatcher) {
            open()
            // Off: neither note has anything to say.
            assertFalse(vm.ui.value.micNeedsPermission)
            assertFalse(vm.ui.value.micMuteHintVisible)

            // On without a grant: the ask, and only the ask.
            vm.setMic(true)
            assertTrue(vm.ui.value.micNeedsPermission)
            assertFalse(vm.ui.value.micMuteHintVisible)

            // On with the grant: armed, so where the mute controls are is what matters.
            granted = true
            vm.refreshMicPermission()
            assertFalse(vm.ui.value.micNeedsPermission)
            assertTrue(vm.ui.value.micMuteHintVisible)
        }

    @Test
    fun `a denied grant leaves the row asking rather than silently armed`() =
        runTest(dispatcher) {
            open()
            vm.setMic(true)
            // The launcher's result is a refresh either way; a refusal must not flip the row into
            // a state that claims a working microphone.
            granted = false
            vm.refreshMicPermission()
            assertTrue(vm.ui.value.micNeedsPermission)
            assertFalse(vm.ui.value.micMuteHintVisible)
        }

    @Test
    fun `a grant revoked in system settings puts the row back to needing permission`() =
        runTest(dispatcher) {
            granted = true
            open()
            vm.setMic(true)
            assertTrue(vm.ui.value.micMuteHintVisible)

            granted = false
            vm.refreshMicPermission()
            assertTrue(vm.ui.value.micNeedsPermission)
            assertFalse(vm.ui.value.micMuteHintVisible)
        }

    @Test
    fun `a path with no microphone shows neither note, grant or not`() =
        runTest(dispatcher) {
            pathCaps = capsWith(Feature.SPEAKER)
            granted = true
            open()
            vm.setMic(true)
            assertFalse(vm.ui.value.micAvailable)
            assertFalse(vm.ui.value.micNeedsPermission)
            assertFalse(vm.ui.value.micMuteHintVisible)
        }

    private companion object {
        const val SLOT = "9"
        const val HOST = "host-1"
        const val TYPE_DUALSENSE = 2
    }
}
