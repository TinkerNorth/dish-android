// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
 * Unit tests for [MainViewModel]. The hub, satellite manager, and physical
 * gamepad registry are stubbed so the VM is exercised in isolation; only the
 * slot derivation + bind/unbind forwarding is under test here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var hub: ConnectionHub
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var gamepadRegistry: PhysicalGamepadRegistry
    private lateinit var batteryStore: BatteryStatusStore
    private lateinit var motionEnabledStore: MotionEnabledStore
    private lateinit var motionCapabilityComposer: MotionCapabilityComposer
    private lateinit var vm: MainViewModel

    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val devicesFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val satelliteEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub = mockk(relaxed = true)
        satellite = mockk(relaxed = true)
        gamepadRegistry = mockk(relaxed = true)
        // BatteryStatusStore is a pure JVM holder (no Android deps) — use the
        // real thing so battery samples really thread through to the slots.
        batteryStore = BatteryStatusStore()
        // MotionEnabledStore is hydrated from MotionPreferenceRepository at
        // construction. Stub the repo so the store's init reads emptyMap()
        // (the relevant default for these slot-wiring tests).
        motionEnabledStore =
            MotionEnabledStore(
                mockk(relaxed = true) { every { all() } returns emptyList() },
            )
        // MotionCapabilityComposer is exercised separately; the VM only
        // forwards its state into MainUiState. A relaxed mock with an
        // empty initial-state flow is enough to satisfy the constructor.
        motionCapabilityComposer =
            mockk(relaxed = true) {
                every { state } returns
                    kotlinx.coroutines.flow.MutableStateFlow(
                        emptyMap<String, com.tinkernorth.dish.composer.MotionCapability>(),
                    )
            }
        every { hub.connections } returns connectionsFlow
        every { hub.bindings } returns bindingsFlow
        every { gamepadRegistry.devices } returns devicesFlow
        every { satellite.events } returns satelliteEvents
        // The VM resolves the virtual-slot label from string resources via the
        // injected Context. A relaxed mock returns "" by default, which keeps
        // the existing assertions (focused on slot wiring, not the label) green.
        val context = mockk<Context>(relaxed = true)
        vm =
            MainViewModel(
                context,
                satellite,
                hub,
                gamepadRegistry,
                batteryStore,
                motionEnabledStore,
                motionCapabilityComposer,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has virtual slot only and no connections`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            val st = vm.uiState.value
            assertEquals(1, st.slots.size)
            assertEquals(VIRTUAL_SLOT_ID, st.slots.first().id)
            assertEquals(SlotInputType.VIRTUAL, st.slots.first().inputType)
            assertTrue(st.connections.isEmpty())
        }

    @Test
    fun `registry device appears as a physical slot keyed by device id`() =
        runTest(dispatcher) {
            devicesFlow.value =
                mapOf(42 to PhysicalGamepadRegistry.Device(42, "Xbox Wireless Controller"))
            dispatcher.scheduler.runCurrent()

            val slots = vm.uiState.value.slots
            assertEquals(2, slots.size)
            val added = slots.first { it.inputType == SlotInputType.PHYSICAL }
            assertEquals("42", added.id)
            assertEquals(42, added.physicalDeviceId)
            assertEquals("Xbox Wireless Controller", added.name)
        }

    @Test
    fun `re-emitting the same device id does not duplicate the slot`() =
        runTest(dispatcher) {
            val pad = PhysicalGamepadRegistry.Device(7, "A")
            devicesFlow.value = mapOf(7 to pad)
            devicesFlow.value = mapOf(7 to pad)
            dispatcher.scheduler.runCurrent()

            assertEquals(2, vm.uiState.value.slots.size) // virtual + one physical
        }

    @Test
    fun `dropping a device id removes the matching slot`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(3 to PhysicalGamepadRegistry.Device(3, "Pad"))
            dispatcher.scheduler.runCurrent()
            devicesFlow.value = emptyMap()
            dispatcher.scheduler.runCurrent()

            val slots = vm.uiState.value.slots
            assertEquals(1, slots.size)
            assertEquals(VIRTUAL_SLOT_ID, slots.first().id)
        }

    @Test
    fun `bindSlot delegates to hub`() {
        vm.bindSlot(slotId = "slot-X", connectionId = "s:1")
        verify { hub.bind("slot-X", "s:1") }
    }

    @Test
    fun `unbindSlot delegates to hub`() {
        vm.unbindSlot(slotId = "slot-X")
        verify { hub.unbind("slot-X") }
    }

    @Test
    fun `setSatelliteControllerType delegates to hub`() {
        vm.setSatelliteControllerType(connectionId = "s:1", slotId = "slot-X", type = 1)
        verify { hub.setSatelliteControllerType("s:1", "slot-X", 1) }
    }

    @Test
    fun `setMotionEnabled writes through to the motion store`() {
        // The dashboard's toggle should be a thin pass-through; the durable
        // write happens in MotionEnabledStore. Pin that the VM does not
        // accidentally short-circuit (e.g. only updating local state).
        vm.setMotionEnabled(slotId = "9", enabled = false)
        assertEquals(false, motionEnabledStore.state.value["9"])
        assertEquals(false, motionEnabledStore.isEnabled("9"))
    }

    @Test
    fun `isMotionEnabled defaults to true for a slot that has not been toggled`() {
        // Product default — flip in MotionEnabledStore.DEFAULT_ENABLED if
        // policy changes. Pinned here so a regression in the VM accessor
        // (e.g. reading the raw map and treating null as false) fails.
        assertTrue(vm.isMotionEnabled("never-touched"))
    }

    @Test
    fun `motionEnabled flow forwards the underlying store state`() {
        // Bind point for the dashboard adapter: a write through the VM
        // must be observable on its motionEnabled flow.
        vm.setMotionEnabled(slotId = "virtual", enabled = false)
        assertEquals(false, vm.motionEnabled.value["virtual"])
    }

    @Test
    fun `hub bindings are reflected as boundConnectionId on slots`() =
        runTest(dispatcher) {
            val summary =
                ConnectionSummary(
                    id = "s:1",
                    kind = ConnectionKind.SATELLITE,
                    label = "PC",
                    detail = "1.1.1.1",
                    live = LinkState.Connected,
                    boundSlotIds = listOf(VIRTUAL_SLOT_ID),
                )
            connectionsFlow.value = listOf(summary)
            bindingsFlow.value = mapOf(VIRTUAL_SLOT_ID to "s:1")
            dispatcher.scheduler.runCurrent()

            val virtual =
                vm.uiState.value.slots
                    .first { it.id == VIRTUAL_SLOT_ID }
            assertEquals("s:1", virtual.boundConnectionId)
            assertEquals(summary, virtual.boundStatus)
        }

    @Test
    fun `battery store sample is surfaced onto the matching slot`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(9 to PhysicalGamepadRegistry.Device(9, "Pad"))
            batteryStore.put(
                "9",
                BatterySample(72, com.tinkernorth.dish.source.sensor.BatteryValidator.STATUS_DISCHARGING),
            )
            dispatcher.scheduler.runCurrent()

            val pad =
                vm.uiState.value.slots
                    .first { it.id == "9" }
            assertEquals(72, pad.battery?.level)
            assertEquals(false, pad.battery?.charging)
        }

    @Test
    fun `slot with no reported battery has a null battery field`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(9 to PhysicalGamepadRegistry.Device(9, "Pad"))
            dispatcher.scheduler.runCurrent()

            assertNull(
                vm.uiState.value.slots
                    .first { it.id == "9" }
                    .battery,
            )
        }

    @Test
    fun `unbound slot has null bound fields`() =
        runTest(dispatcher) {
            connectionsFlow.value = emptyList()
            bindingsFlow.value = emptyMap()
            dispatcher.scheduler.runCurrent()

            val virtual =
                vm.uiState.value.slots
                    .first { it.id == VIRTUAL_SLOT_ID }
            assertNull(virtual.boundConnectionId)
            assertNull(virtual.boundStatus)
        }
}
