// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.lowpower.LowPowerSignal
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var hub: ConnectionCoordinator
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var gamepadRegistry: PhysicalGamepadRegistry
    private lateinit var batteryStore: BatteryStatusStore
    private lateinit var motionEnabledStore: MotionEnabledStore
    private lateinit var capabilityComposer: CapabilityComposer
    private lateinit var touchpadModeStore: TouchpadModeStore
    private lateinit var native: PhysicalInputNative
    private lateinit var pathPrefs: UsbPathPreferenceStore
    private lateinit var usbGamepadManager: UsbGamepadManager
    private lateinit var inputRateStore: InputRateStore
    private lateinit var vm: MainViewModel

    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val devicesFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val capabilityStateFlow = MutableStateFlow<Map<String, SlotCapabilities>>(emptyMap())
    private val satelliteEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub = mockk(relaxed = true)
        satellite = mockk(relaxed = true)
        gamepadRegistry = mockk(relaxed = true)
        batteryStore = BatteryStatusStore()
        motionEnabledStore =
            MotionEnabledStore(
                mockk(relaxed = true) { every { all() } returns emptyList() },
            )
        capabilityStateFlow.value = emptyMap()
        capabilityComposer =
            mockk(relaxed = true) {
                every { state } returns capabilityStateFlow
            }
        touchpadModeStore =
            TouchpadModeStore(
                mockk(relaxed = true) { every { all() } returns emptyList() },
            )
        native = mockk(relaxed = true)
        pathPrefs = mockk(relaxed = true)
        usbGamepadManager = mockk(relaxed = true)
        every { hub.connections } returns connectionsFlow
        every { hub.bindings } returns bindingsFlow
        every { gamepadRegistry.devices } returns devicesFlow
        every { gamepadRegistry.frameworkCapsFor(any(), any()) } returns null
        every { satellite.events } returns satelliteEvents
        every { pathPrefs.state } returns MutableStateFlow(emptyMap())
        inputRateStore =
            InputRateStore(
                gamepadRegistry,
                native,
                LowPowerSignal(),
                CoroutineScope(SupervisorJob()),
            )
        val context = mockk<Context>(relaxed = true)
        vm =
            MainViewModel(
                context,
                satellite,
                hub,
                gamepadRegistry,
                batteryStore,
                motionEnabledStore,
                capabilityComposer,
                touchpadModeStore,
                native,
                pathPrefs,
                usbGamepadManager,
                inputRateStore,
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

            assertEquals(2, vm.uiState.value.slots.size)
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
    fun `measured input rates and the screen peak reach ui state keyed by slot id`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(7 to PhysicalGamepadRegistry.Device(7, "Pad"))
            every { native.getDeviceInputEventCount(7) } returns 0L
            inputRateStore.sampleAll(nowMs = 1000L)
            every { native.getDeviceInputEventCount(7) } returns 60L
            repeat(60) { inputRateStore.recordScreenSample() }
            inputRateStore.sampleAll(nowMs = 1500L)
            dispatcher.scheduler.runCurrent()

            val st = vm.uiState.value
            assertEquals(120, st.inputRates["7"]?.controllerHz)
            assertEquals(120, st.inputRates["7"]?.controllerPeakHz)
            assertEquals(120, st.screenPeakHz)
        }

    @Test
    fun `rates for a slot that is no longer present are not surfaced`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(7 to PhysicalGamepadRegistry.Device(7, "Pad"))
            every { native.getDeviceInputEventCount(7) } returns 0L
            inputRateStore.sampleAll(nowMs = 1000L)
            every { native.getDeviceInputEventCount(7) } returns 60L
            inputRateStore.sampleAll(nowMs = 1500L)
            devicesFlow.value = emptyMap()
            inputRateStore.sampleAll(nowMs = 2000L)
            dispatcher.scheduler.runCurrent()

            assertEquals(0, vm.uiState.value.inputRates.size)
        }

    @Test
    fun `bindSlot delegates to hub with the slot's remembered type`() {
        every { hub.satTypes } returns
            kotlinx.coroutines.flow.MutableStateFlow(
                mapOf(("s:1" to "slot-X") to com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION),
            )
        vm.bindSlot(slotId = "slot-X", connectionId = "s:1")
        verify { hub.bind("slot-X", "s:1", com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION) }
    }

    @Test
    fun `bindSlot falls back to Xbox only when no choice was ever made`() {
        every { hub.satTypes } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        vm.bindSlot(slotId = "slot-X", connectionId = "s:1")
        verify { hub.bind("slot-X", "s:1", com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX) }
    }

    @Test
    fun `unbindSlot delegates to hub`() {
        vm.unbindSlot(slotId = "slot-X")
        verify { hub.unbind("slot-X") }
    }

    @Test
    fun `touchpad ui reads the wire projection per slot and blocks the overlay for a pad source`() =
        runTest(dispatcher) {
            every { capabilityComposer.touchpadWireMode(VIRTUAL_SLOT_ID) } returns "ds4"
            every { capabilityComposer.touchpadSource(VIRTUAL_SLOT_ID) } returns
                com.tinkernorth.dish.composer.TouchpadSource.PHONE
            every { capabilityComposer.touchpadWireMode("9") } returns "ds4"
            every { capabilityComposer.touchpadSource("9") } returns
                com.tinkernorth.dish.composer.TouchpadSource.PAD
            capabilityStateFlow.value =
                mapOf(VIRTUAL_SLOT_ID to SlotCapabilities.NONE, "9" to SlotCapabilities.NONE)
            dispatcher.scheduler.runCurrent()

            val map = vm.uiState.value.touchpadBySlot
            assertEquals(TouchpadSlotUi(mode = "ds4", phoneSourced = true), map[VIRTUAL_SLOT_ID])
            assertTrue(map.getValue(VIRTUAL_SLOT_ID).openable)
            assertEquals(TouchpadSlotUi(mode = "ds4", phoneSourced = false), map["9"])
            // The pad streams its own trackpad: no phone overlay for the slot.
            assertEquals(false, map.getValue("9").openable)
        }

    @Test
    fun `setSatelliteControllerType delegates to hub`() {
        vm.setSatelliteControllerType(connectionId = "s:1", slotId = "slot-X", type = 1)
        verify { hub.setSatelliteControllerType("s:1", "slot-X", 1) }
    }

    @Test
    fun `setMotionEnabled writes through to the motion store`() {
        vm.setMotionEnabled(slotId = "9", enabled = false)
        assertEquals(false, motionEnabledStore.state.value["9"])
        assertEquals(false, motionEnabledStore.isEnabled("9"))
    }

    @Test
    fun `isMotionEnabled defaults to true for a slot that has not been toggled`() {
        assertTrue(vm.isMotionEnabled("never-touched"))
    }

    @Test
    fun `motionEnabled flow forwards the underlying store state`() {
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

    private fun synthetic(
        id: Int,
        vid: Int,
        pid: Int,
        pollRateHz: Int = 0,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad",
        isUsbSynthetic = true,
        pollRateHz = pollRateHz,
        vendorId = vid,
        productId = pid,
    )

    private fun routed(
        id: Int,
        vid: Int,
        pid: Int,
        disconnecting: Boolean = false,
        transport: Transport = Transport.Usb,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad",
        disconnectingTimeLeftSec = if (disconnecting) 3 else null,
        isUsbSynthetic = false,
        vendorId = vid,
        productId = pid,
        transport = transport,
    )

    @Test
    fun `synthetic device appears as a Direct-mode physical slot`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(-1000 to synthetic(-1000, 1, 2, pollRateHz = 1000))
            dispatcher.scheduler.runCurrent()

            val slot =
                vm.uiState.value.slots
                    .first { it.inputType == SlotInputType.PHYSICAL }
            assertEquals("-1000", slot.id)
            assertEquals(
                InputPathMode.Direct,
                vm.uiState.value.pathCards["-1000"]
                    ?.currentMode,
            )
        }

    @Test
    fun `a routed twin of a claimed synthetic is hidden from the slot list`() =
        runTest(dispatcher) {
            devicesFlow.value =
                mapOf(
                    -1000 to synthetic(-1000, 1, 2),
                    50 to routed(50, 1, 2),
                )
            dispatcher.scheduler.runCurrent()

            val physical =
                vm.uiState.value.slots
                    .filter { it.inputType == SlotInputType.PHYSICAL }
            assertEquals(1, physical.size)
            assertEquals("-1000", physical.first().id)
        }

    @Test
    fun `a second identical routed controller stays visible alongside one synthetic`() =
        runTest(dispatcher) {
            devicesFlow.value =
                mapOf(
                    -1000 to synthetic(-1000, 1, 2),
                    50 to routed(50, 1, 2),
                    51 to routed(51, 1, 2),
                )
            dispatcher.scheduler.runCurrent()

            val ids =
                vm.uiState.value.slots
                    .filter { it.inputType == SlotInputType.PHYSICAL }
                    .map { it.id }
            assertEquals(2, ids.size)
            assertTrue(ids.contains("-1000"))
        }

    @Test
    fun `the disconnecting twin is hidden first, keeping the live duplicate listed`() =
        runTest(dispatcher) {
            devicesFlow.value =
                mapOf(
                    -1000 to synthetic(-1000, 1, 2),
                    50 to routed(50, 1, 2, disconnecting = false),
                    51 to routed(51, 1, 2, disconnecting = true),
                )
            dispatcher.scheduler.runCurrent()

            val ids =
                vm.uiState.value.slots
                    .filter { it.inputType == SlotInputType.PHYSICAL }
                    .map { it.id }
            assertTrue("live duplicate kept", ids.contains("50"))
            assertTrue("disconnecting twin hidden", !ids.contains("51"))
        }

    @Test
    fun `recognised routed controller reads as Standard until it actually claims`() =
        runTest(dispatcher) {
            every { native.isKnownFastLaneModel(0x045E, 0x028E) } returns true
            devicesFlow.value = mapOf(60 to routed(60, 0x045E, 0x028E))
            dispatcher.scheduler.runCurrent()

            val card = vm.uiState.value.pathCards["60"]
            // Recognised (Direct offered, no risk), but the switch shows the live Standard mode until a
            // synthetic is actually claimed; it must never pre-select Direct.
            assertEquals(InputPathMode.Standard, card?.currentMode)
            assertEquals(PathChoice.Standard, card?.selected)
            assertEquals(PathRisk.None, card?.risk)
        }

    @Test
    fun `a bluetooth controller's card reports bluetooth and offers no direct path`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(70 to routed(70, 1, 2, transport = Transport.Bluetooth))
            dispatcher.scheduler.runCurrent()

            val card = vm.uiState.value.pathCards["70"]
            assertEquals(Transport.Bluetooth, card?.transport)
            assertEquals(false, card?.directAvailable)
        }

    @Test
    fun `the virtual slot has no path card`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            assertNull(vm.uiState.value.pathCards[VIRTUAL_SLOT_ID])
        }

    @Test
    fun `every physical slot has a path card and the virtual slot does not`() =
        runTest(dispatcher) {
            devicesFlow.value =
                mapOf(
                    -1000 to synthetic(-1000, 1, 2),
                    60 to routed(60, 0x045E, 0x028E),
                )
            dispatcher.scheduler.runCurrent()

            val physicalIds =
                vm.uiState.value.slots
                    .filter { it.inputType == SlotInputType.PHYSICAL }
                    .map { it.id }
                    .toSet()
            assertEquals(physicalIds, vm.uiState.value.pathCards.keys)
        }

    @Test
    fun `a device held mid-switch surfaces a restoring path card`() =
        runTest(dispatcher) {
            devicesFlow.value = mapOf(-1000 to synthetic(-1000, 1, 2).copy(transitioning = true))
            dispatcher.scheduler.runCurrent()

            assertEquals(
                true,
                vm.uiState.value.pathCards["-1000"]
                    ?.restoring,
            )
        }
}
