// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.InputFunctions
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SlotToggleStores
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import com.tinkernorth.dish.source.system.MicPermissionGate
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.PhysicalPadSources
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The apply flow end to end: which terminal state each failure lands in, what the step
 * overlay counts, how long the USB machine and the satellite are waited on, and which slot
 * id the bind goes out under after a path switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureBindingsApplyTest {
    private val dispatcher = StandardTestDispatcher()

    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val satTypesFlow = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
    private val devicesFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val usbControllersFlow = MutableStateFlow<Map<Int, UsbController>>(emptyMap())
    private val satelliteSlotsFlow = MutableStateFlow<Map<String, SatelliteConnection.SlotBinding>>(emptyMap())

    private lateinit var hub: ConnectionCoordinator
    private lateinit var usb: UsbGamepadManager
    private lateinit var motionStore: MotionEnabledStore
    private lateinit var rumbleStore: RumbleEnabledStore
    private lateinit var vm: ConfigureBindingsViewModel

    private val framework =
        PhysicalGamepadRegistry.Device(id = FRAMEWORK_ID, name = "DualSense", vendorId = VID, productId = PID)
    private val synthetic =
        PhysicalGamepadRegistry.Device(id = SYNTHETIC_ID, name = "DualSense", isUsbSynthetic = true, vendorId = VID, productId = PID)
    private val bluetoothPad =
        PhysicalGamepadRegistry.Device(id = FRAMEWORK_ID, name = "DualSense", transport = Transport.Bluetooth)

    private fun res(id: Int): String = "res:$id"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } answers { res(firstArg()) }
        every { context.getString(any(), *anyVararg()) } answers { res(firstArg()) }

        hub = mockk(relaxed = true)
        every { hub.connections } returns connectionsFlow
        every { hub.bindings } returns bindingsFlow
        every { hub.satTypes } returns satTypesFlow
        every { hub.bind(any(), any(), any()) } returns true
        every { hub.summary(any()) } answers { connectionsFlow.value.firstOrNull { it.id == firstArg<String>() } }

        val registry: PhysicalGamepadRegistry = mockk(relaxed = true)
        every { registry.devices } returns devicesFlow
        usb = mockk(relaxed = true)
        every { usb.controllers } returns usbControllersFlow
        val native: PhysicalInputNative = mockk(relaxed = true)
        every { native.isKnownFastLaneModel(any(), any()) } returns true

        val composer: CapabilityComposer = mockk(relaxed = true)
        every { composer.capabilityFor(any()) } returns SlotCapabilities.NONE
        every { composer.capabilityForCandidate(any(), any(), any(), any(), any()) } returns SlotCapabilities.NONE
        every { composer.inputFunctionsFor(any(), any()) } returns
            InputFunctions(known = true, rumble = false, gyro = false, touchpad = false)

        val satellite: SatelliteConnectionManager = mockk(relaxed = true)
        val conn: SatelliteConnection = mockk(relaxed = true)
        every { conn.server } returns MutableStateFlow(mockk(relaxed = true))
        every { conn.slots } returns satelliteSlotsFlow
        every { satellite.get(any()) } answers { if (firstArg<String>() == SATELLITE_ID) conn else null }

        val catalogRepo: SatelliteCatalogRepository = mockk(relaxed = true)
        every { catalogRepo.cached(any()) } returns null
        coEvery { catalogRepo.catalogFor(any(), any()) } returns null
        val capabilitiesRepo: SatelliteCapabilitiesRepository = mockk(relaxed = true)
        coEvery { capabilitiesRepo.refresh(any(), any()) } returns null

        motionStore = mockk(relaxed = true)
        rumbleStore = mockk(relaxed = true)
        vm =
            ConfigureBindingsViewModel(
                context = context,
                hub = hub,
                pads = PhysicalPadSources(registry, native, usb, mockk(relaxed = true)),
                toggles =
                    SlotToggleStores(
                        motion = motionStore,
                        rumble = rumbleStore,
                        mic = mockk<MicEnabledStore>(relaxed = true),
                        speaker = mockk<SpeakerEnabledStore>(relaxed = true),
                    ),
                micPermission = mockk<MicPermissionGate>(relaxed = true),
                capabilityComposer = composer,
                satellite = satellite,
                moonlight = mockk<MoonlightConnectionManager>(relaxed = true),
                hostFacts =
                    SatelliteHostFacts(
                        features = SatelliteHostFeaturesStore(),
                        runtime = mockk(),
                        motionBackend = mockk(),
                        catalog = catalogRepo,
                        capabilities = capabilitiesRepo,
                    ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun summary(
        id: String,
        kind: ConnectionKind,
        live: LinkState,
    ) = ConnectionSummary(id = id, kind = kind, label = "PC", detail = "", live = live, boundSlotIds = listOf(slotId()))

    private fun slotId(): String = FRAMEWORK_ID.toString()

    private fun controller(
        phase: UsbPhase,
        desired: PathChoice = PathChoice.Direct,
    ) = mapOf(KEY to UsbController(vendorId = VID, productId = PID, name = "DualSense", phase = phase, desired = desired))

    private fun registered(slotId: String) =
        mapOf(slotId to SatelliteConnection.SlotBinding(controllerIndex = 0, controllerType = TYPE, registered = true))

    // A remembered type on a bound slot: the screen opens ready to apply.
    private fun open(
        host: ConnectionSummary = summary(SATELLITE_ID, ConnectionKind.SATELLITE, LinkState.Connected),
        device: PhysicalGamepadRegistry.Device = framework,
        typeRemembered: Boolean = true,
    ) {
        devicesFlow.value = mapOf(device.id to device)
        connectionsFlow.value = listOf(host)
        bindingsFlow.value = mapOf(slotId() to host.id)
        if (typeRemembered) satTypesFlow.value = mapOf((host.id to slotId()) to TYPE)
        vm.load(slotId())
        vm.setHost(host.id)
        dispatcher.scheduler.runCurrent()
    }

    private fun finished(): ApplyState.Finished = vm.applyState.value as ApplyState.Finished

    private fun running(): ApplyState.Running = vm.applyState.value as ApplyState.Running

    @Test
    fun `apply with an unresolved type refuses and stays idle`() =
        runTest(dispatcher) {
            open(typeRemembered = false)
            assertNull(
                vm.ui.value.draft
                    ?.type,
            )

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ApplyState.Idle, vm.applyState.value)
            verify(exactly = 0) { hub.bind(any(), any(), any()) }
        }

    @Test
    fun `apply while an apply is running is ignored`() =
        runTest(dispatcher) {
            open()
            vm.setDirect(false)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.applyState.value is ApplyState.Running)
            vm.apply()
            satelliteSlotsFlow.value = registered(slotId())
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { hub.bind(any(), any(), any()) }
            assertNull(finished().errorMessage)
        }

    @Test
    fun `apply when the slot vanished before bind finishes with the slot gone error`() =
        runTest(dispatcher) {
            every { hub.bind(any(), any(), any()) } returns false
            open()
            vm.setDirect(false)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(res(R.string.binding_apply_error_slot_gone), finished().errorMessage)
            assertEquals("DualSense", finished().controllerName)
        }

    @Test
    fun `apply when the host never comes up finishes with the no connect error`() =
        runTest(dispatcher) {
            open(host = summary(SATELLITE_ID, ConnectionKind.SATELLITE, LinkState.Saved))
            vm.setDirect(false)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(res(R.string.binding_apply_error_no_connect), finished().errorMessage)
            assertEquals("PC", finished().hostName)
        }

    @Test
    fun `apply when direct was asked for and did not take finishes with a warning`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.NeedsReplug)
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            verify { usb.setPathChoice(VID, PID, PathChoice.Direct) }
            assertNull(finished().errorMessage)
            assertEquals(res(R.string.binding_apply_warn_detail), finished().warningMessage)
        }

    @Test
    fun `a standard pick applies at once without waiting on the usb machine`() =
        runTest(dispatcher) {
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(false)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            verify { usb.setPathChoice(VID, PID, PathChoice.Standard) }
            assertNull(finished().errorMessage)
            assertNull(finished().warningMessage)
        }

    @Test
    fun `applyUsbPath waits for the claim to settle and reports that direct took`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Claiming)
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            assertEquals(0, running().doneCount)
            usbControllersFlow.value = controller(UsbPhase.Direct)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(finished().warningMessage)
        }

    @Test
    fun `applyUsbPath that lands on restore stuck reports direct not taken`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.RestoreStuck)
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(res(R.string.binding_apply_warn_detail), finished().warningMessage)
        }

    @Test
    fun `a refused permission settles the claim back on routed as not taken`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Routed, desired = PathChoice.Standard)
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(res(R.string.binding_apply_warn_detail), finished().warningMessage)
        }

    @Test
    fun `a routed pad still wanting direct is the open permission prompt, so the wait goes on`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Routed, desired = PathChoice.Direct)
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            assertEquals(0, running().doneCount)
            usbControllersFlow.value = controller(UsbPhase.Direct)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(finished().warningMessage)
        }

    @Test
    fun `applyUsbPath that never settles times out as not taken`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Claiming)
            satelliteSlotsFlow.value = registered(slotId())
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(res(R.string.binding_apply_warn_detail), finished().warningMessage)
        }

    @Test
    fun `a finished step and the active step are drawn opaque`() {
        assertEquals(OPAQUE, applyStepAlpha(index = 0, doneCount = 1), EXACT)
        assertEquals(OPAQUE, applyStepAlpha(index = 1, doneCount = 1), EXACT)
    }

    @Test
    fun `a step still to come is faded to half`() {
        assertEquals(HALF_FADED, applyStepAlpha(index = 2, doneCount = 1), EXACT)
    }

    @Test
    fun `the step counter advances once per real async action`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Claiming)
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            assertEquals(ApplyState.Running(twoSteps(), doneCount = 0), vm.applyState.value)
            usbControllersFlow.value = controller(UsbPhase.Direct)
            dispatcher.scheduler.runCurrent()
            assertEquals(ApplyState.Running(twoSteps(), doneCount = 1), vm.applyState.value)
            satelliteSlotsFlow.value = registered(slotId())
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(finished().errorMessage)
        }

    @Test
    fun `apply after a direct switch binds the synthetic twin not the retired framework id`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Claiming)
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            devicesFlow.value = mapOf(SYNTHETIC_ID to synthetic)
            usbControllersFlow.value = controller(UsbPhase.Direct)
            satelliteSlotsFlow.value = registered(SYNTHETIC_ID.toString())
            dispatcher.scheduler.advanceUntilIdle()

            verify { hub.bind(SYNTHETIC_ID.toString(), SATELLITE_ID, TYPE) }
            verify { rumbleStore.setEnabled(SYNTHETIC_ID.toString(), any()) }
            assertNull(finished().errorMessage)
        }

    @Test
    fun `a satellite binding is applied only once the slot is registered`() =
        runTest(dispatcher) {
            open()
            vm.setDirect(false)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.applyState.value is ApplyState.Running)
            satelliteSlotsFlow.value = registered(slotId())
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(finished().errorMessage)
        }

    @Test
    fun `a satellite slot that never registers times out`() =
        runTest(dispatcher) {
            open()
            vm.setDirect(false)

            vm.apply()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(res(R.string.binding_apply_error_no_connect), finished().errorMessage)
        }

    @Test
    fun `a bluetooth binding is applied once the link is up`() =
        runTest(dispatcher) {
            val host = summary(BT_ID, ConnectionKind.BLUETOOTH, LinkState.Connecting)
            open(host = host, device = bluetoothPad)

            vm.apply()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.applyState.value is ApplyState.Running)
            connectionsFlow.value = listOf(host.copy(live = LinkState.Connected))
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(finished().errorMessage)
        }

    @Test
    fun `a bluetooth binding has no direct step`() =
        runTest(dispatcher) {
            val host = summary(BT_ID, ConnectionKind.BLUETOOTH, LinkState.Connecting)
            open(host = host, device = bluetoothPad)

            vm.apply()
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(res(R.string.binding_label_destination)), running().steps.map { it.label })
        }

    @Test
    fun `the step list has a direct step only for a direct capable usb pad`() =
        runTest(dispatcher) {
            usbControllersFlow.value = controller(UsbPhase.Claiming)
            open()
            vm.setDirect(true)

            vm.apply()
            dispatcher.scheduler.runCurrent()

            assertEquals(twoSteps(), running().steps)
        }

    private fun twoSteps(): List<ApplyStep> =
        listOf(
            ApplyStep("direct", res(R.string.binding_label_connection)),
            ApplyStep("apply", res(R.string.binding_label_destination)),
        )

    private companion object {
        const val SATELLITE_ID = "satellite:mid:abc"
        const val BT_ID = "bt:AA:BB"
        const val FRAMEWORK_ID = 60
        const val SYNTHETIC_ID = -1000
        const val VID = 0x054C
        const val PID = 0x0CE6
        const val KEY = (VID shl 16) or PID
        const val TYPE = 2
        const val HALF_FADED = 0.5f
        const val OPAQUE = 1f
        const val EXACT = 0f
    }
}
