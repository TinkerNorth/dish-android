// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.LegacyCatalogTranslator
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The "Emulate as" type is host-owned (thin-catalog): a satellite host has NO guessed default before
// its catalog resolves — the type is UNRESOLVED (null) and the screen shows a loader (TypeLoad.Loading),
// snapping to the catalog's first offered type on arrival, surfacing Error if the fetch fails with
// nothing cached, and never clobbering a remembered/manual pick. A Bluetooth host is always Ready.
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureBindingsDefaultTypeTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var hub: ConnectionCoordinator
    private lateinit var gamepadRegistry: PhysicalGamepadRegistry
    private lateinit var motionEnabledStore: MotionEnabledStore
    private lateinit var rumbleEnabledStore: RumbleEnabledStore
    private lateinit var capabilityComposer: CapabilityComposer
    private lateinit var touchpadModeStore: TouchpadModeStore
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var usbGamepadManager: UsbGamepadManager
    private lateinit var catalogRepo: SatelliteCatalogRepository
    private lateinit var capabilitiesRepo: SatelliteCapabilitiesRepository
    private lateinit var native: PhysicalInputNative
    private lateinit var vm: ConfigureBindingsViewModel

    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val satTypesFlow = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val devicesFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())

    private val server = DiscoveredServer(name = "PC", ip = "10.0.0.5", udpPort = 9876, machineId = "m1")

    private val xboxThenDs4 =
        CatalogDto(
            controllerTypes =
                listOf(
                    CatalogTypeDto(id = TYPE_XBOX360, slug = "xbox360", name = "Xbox 360 Controller"),
                    CatalogTypeDto(id = TYPE_DS4, slug = "ds4", name = "DualShock 4"),
                ),
        )
    private val ds4ThenXbox =
        CatalogDto(
            controllerTypes =
                listOf(
                    CatalogTypeDto(id = TYPE_DS4, slug = "ds4", name = "DualShock 4"),
                    CatalogTypeDto(id = TYPE_XBOX360, slug = "xbox360", name = "Xbox 360 Controller"),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = mockk(relaxed = true)
        hub = mockk(relaxed = true)
        gamepadRegistry = mockk(relaxed = true)
        motionEnabledStore = mockk(relaxed = true)
        rumbleEnabledStore = mockk(relaxed = true)
        touchpadModeStore = mockk(relaxed = true)
        capabilityComposer = mockk(relaxed = true)
        satellite = mockk(relaxed = true)
        usbGamepadManager = mockk(relaxed = true)
        catalogRepo = mockk(relaxed = true)
        capabilitiesRepo = mockk(relaxed = true)
        native = mockk(relaxed = true)

        every { hub.bindings } returns bindingsFlow
        every { hub.satTypes } returns satTypesFlow
        every { hub.connections } returns connectionsFlow
        every { gamepadRegistry.devices } returns devicesFlow
        every { usbGamepadManager.controllers } returns MutableStateFlow(emptyMap())
        every { touchpadModeStore.modeFor(any()) } returns TouchpadModeValue.OFF
        every { capabilityComposer.capabilityFor(any()) } returns SlotCapabilities.NONE
        every { capabilityComposer.capabilityForCandidate(any(), any(), any(), any()) } returns SlotCapabilities.NONE

        val conn = mockk<SatelliteConnection>(relaxed = true)
        every { conn.server } returns MutableStateFlow(server)
        every { satellite.get(HOST) } returns conn
        every { catalogRepo.cached(any()) } returns null
        coEvery { capabilitiesRepo.refresh(any(), any()) } returns null

        vm =
            ConfigureBindingsViewModel(
                context,
                hub,
                gamepadRegistry,
                motionEnabledStore,
                rumbleEnabledStore,
                capabilityComposer,
                touchpadModeStore,
                satellite,
                usbGamepadManager,
                catalogRepo,
                capabilitiesRepo,
                native,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun currentDraft() = vm.ui.value.draft

    private fun satelliteHost(
        id: String = HOST,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(id = id, kind = ConnectionKind.SATELLITE, label = "PC", detail = "", live = live, boundSlotIds = emptyList())

    private fun bluetoothHost(
        id: String = BT_HOST,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(id = id, kind = ConnectionKind.BLUETOOTH, label = "Pad", detail = "", live = live, boundSlotIds = emptyList())

    @Test
    fun `no remembered binding and no catalog yet leaves the type unresolved with a loader and Apply disabled`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            // Fetch never completes within the test window (not advanced), so the catalog is still loading.
            vm.load(SLOT)
            vm.setHost(HOST)
            // No advanceUntilIdle: the fetch coroutine is queued but has not run.
            assertNull(currentDraft()?.type)
            assertEquals(TypeLoad.Loading, vm.ui.value.typeLoad)
            assertFalse(vm.ui.value.canApply)
        }

    @Test
    fun `a ds4-first catalog snaps the type to the first offered id, Ready and applyable`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            coEvery { catalogRepo.catalogFor(any(), any()) } returns ds4ThenXbox
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_DS4, currentDraft()?.type)
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
            assertTrue(vm.ui.value.canApply)
        }

    @Test
    fun `an xbox-first catalog snaps the type to xbox`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            coEvery { catalogRepo.catalogFor(any(), any()) } returns xboxThenDs4
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_XBOX360, currentDraft()?.type)
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
        }

    @Test
    fun `a legacy satellite resolves to xbox via the hardcoded catalog - Ready, applyable, no loader-block`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            // A legacy/absent-version body (even carrying a junk type) normalizes to the hardcoded
            // v1 catalog, xbox first; the repository yields it, so the VM resolves it like any other.
            val hardcodedV1 =
                LegacyCatalogTranslator().normalize(
                    CatalogDto(controllerTypes = listOf(CatalogTypeDto(id = 99, slug = "frobnicator"))),
                )
            coEvery { catalogRepo.catalogFor(any(), any()) } returns hardcodedV1
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_XBOX360, currentDraft()?.type)
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
            assertTrue(vm.ui.value.canApply)
        }

    @Test
    fun `a remembered binding resolves the type immediately and the catalog never overrides it`() =
        runTest(dispatcher) {
            // A pre-bound slot carries its remembered type and triggers the fetch from load().
            connectionsFlow.value = listOf(satelliteHost())
            bindingsFlow.value = mapOf(SLOT to HOST)
            satTypesFlow.value = mapOf((HOST to SLOT) to CONTROLLER_TYPE_SWITCHPRO)
            coEvery { catalogRepo.catalogFor(any(), any()) } returns xboxThenDs4
            vm.load(SLOT)
            // Resolved synchronously from the remembered pick, before the fetch runs.
            assertEquals(CONTROLLER_TYPE_SWITCHPRO, currentDraft()?.type)
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(CONTROLLER_TYPE_SWITCHPRO, currentDraft()?.type)
            assertTrue(vm.ui.value.canApply)
        }

    @Test
    fun `a manual pick survives a later catalog re-publish`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            coEvery { catalogRepo.catalogFor(any(), any()) } returns ds4ThenXbox
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_DS4, currentDraft()?.type) // default snapped to the catalog's first entry

            vm.setType(CONTROLLER_TYPE_SWITCHPRO)
            vm.setHost(HOST) // re-fetches / re-publishes the catalog
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(CONTROLLER_TYPE_SWITCHPRO, currentDraft()?.type)
        }

    @Test
    fun `a failed fetch with nothing cached surfaces Error with no type and Apply disabled - never xbox`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            coEvery { catalogRepo.catalogFor(any(), any()) } returns null
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(currentDraft()?.type)
            assertEquals(TypeLoad.Error, vm.ui.value.typeLoad)
            assertFalse(vm.ui.value.canApply)
        }

    @Test
    fun `retry after a failed fetch resolves the type once the catalog becomes reachable`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            coEvery { catalogRepo.catalogFor(any(), any()) } returns null
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TypeLoad.Error, vm.ui.value.typeLoad)

            coEvery { catalogRepo.catalogFor(any(), any()) } returns ds4ThenXbox
            vm.retryTypeLoad()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_DS4, currentDraft()?.type)
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
        }

    @Test
    fun `a cached catalog snaps the type synchronously, before the fetch coroutine runs`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(satelliteHost())
            every { catalogRepo.cached(HOST) } returns ds4ThenXbox
            coEvery { catalogRepo.catalogFor(any(), any()) } returns ds4ThenXbox
            vm.load(SLOT)
            vm.setHost(HOST)
            assertEquals(TYPE_DS4, currentDraft()?.type)
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
        }

    @Test
    fun `a bluetooth host is always Ready and applyable - unaffected by the catalog`() =
        runTest(dispatcher) {
            connectionsFlow.value = listOf(bluetoothHost())
            every { satellite.get(BT_HOST) } returns null // BT hosts are not in the satellite manager
            vm.load(SLOT)
            vm.setHost(BT_HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TypeLoad.Ready, vm.ui.value.typeLoad)
            assertTrue(vm.ui.value.canApply)
        }

    private companion object {
        const val SLOT = "9"
        const val HOST = "host-1"
        const val BT_HOST = "bt:AA"
        const val TYPE_XBOX360 = 0
        const val TYPE_DS4 = 1
    }
}
