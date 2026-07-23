// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
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
import org.junit.Before
import org.junit.Test

// The "Emulate as" default follows the host's catalog (thin-catalog): the first offered
// type is preselected, not a compile-time Xbox constant, while a remembered binding or a
// manual pick is never clobbered and an unreachable catalog keeps the offline fallback.
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

    @Test
    fun `a ds4-first catalog preselects ds4, proving the default follows the catalog not the xbox constant`() =
        runTest(dispatcher) {
            coEvery { catalogRepo.catalogFor(any(), any()) } returns ds4ThenXbox
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_DS4, currentDraft()?.type)
        }

    @Test
    fun `an xbox-first catalog preselects xbox`() =
        runTest(dispatcher) {
            coEvery { catalogRepo.catalogFor(any(), any()) } returns xboxThenDs4
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(TYPE_XBOX360, currentDraft()?.type)
        }

    @Test
    fun `a remembered binding is not overridden when the catalog arrives`() =
        runTest(dispatcher) {
            // A pre-bound slot carries its remembered type and triggers the fetch from load().
            bindingsFlow.value = mapOf(SLOT to HOST)
            satTypesFlow.value = mapOf((HOST to SLOT) to CONTROLLER_TYPE_SWITCHPRO)
            coEvery { catalogRepo.catalogFor(any(), any()) } returns xboxThenDs4
            vm.load(SLOT)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(CONTROLLER_TYPE_SWITCHPRO, currentDraft()?.type)
        }

    @Test
    fun `a manual pick survives a later catalog re-publish`() =
        runTest(dispatcher) {
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
    fun `an unreachable catalog keeps the offline xbox default`() =
        runTest(dispatcher) {
            coEvery { catalogRepo.catalogFor(any(), any()) } returns null
            vm.load(SLOT)
            vm.setHost(HOST)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(CONTROLLER_TYPE_XBOX, currentDraft()?.type)
        }

    @Test
    fun `a cached catalog snaps the default synchronously, before the fetch coroutine runs`() =
        runTest(dispatcher) {
            every { catalogRepo.cached(HOST) } returns ds4ThenXbox
            coEvery { catalogRepo.catalogFor(any(), any()) } returns ds4ThenXbox
            vm.load(SLOT)
            vm.setHost(HOST)
            assertEquals(TYPE_DS4, currentDraft()?.type)
        }

    private companion object {
        const val SLOT = "9"
        const val HOST = "host-1"
        const val TYPE_XBOX360 = 0
        const val TYPE_DS4 = 1
    }
}
