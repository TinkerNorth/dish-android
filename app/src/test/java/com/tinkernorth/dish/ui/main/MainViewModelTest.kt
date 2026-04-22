package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.data.network.WifiConnectionManager
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
 * Unit tests for [MainViewModel]. The hub and wifi manager are stubbed so the
 * VM is exercised in isolation; only the slot lifecycle + bind/unbind
 * forwarding is under test here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var hub: ConnectionHub
    private lateinit var wifi: WifiConnectionManager
    private lateinit var vm: MainViewModel

    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val wifiEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub = mockk(relaxed = true)
        wifi = mockk(relaxed = true)
        every { hub.connections } returns connectionsFlow
        every { hub.bindings } returns bindingsFlow
        every { wifi.events } returns wifiEvents
        vm = MainViewModel(wifi, hub)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has virtual slot only and no connections`() = runTest(dispatcher) {
        dispatcher.scheduler.runCurrent()
        val st = vm.uiState.value
        assertEquals(1, st.slots.size)
        assertEquals(VIRTUAL_SLOT_ID, st.slots.first().id)
        assertEquals(SlotInputType.VIRTUAL, st.slots.first().inputType)
        assertTrue(st.connections.isEmpty())
    }

    @Test
    fun `onControllerConnected appends a physical slot with the device id`() = runTest(dispatcher) {
        vm.onControllerConnected(id = 42, name = "Xbox Wireless Controller")
        dispatcher.scheduler.runCurrent()

        val slots = vm.uiState.value.slots
        assertEquals(2, slots.size)
        val added = slots.first { it.inputType == SlotInputType.PHYSICAL }
        assertEquals("42", added.id)
        assertEquals(42, added.physicalDeviceId)
        assertEquals("Xbox Wireless Controller", added.name)
    }

    @Test
    fun `onControllerConnected is idempotent for the same device id`() = runTest(dispatcher) {
        vm.onControllerConnected(id = 7, name = "A")
        vm.onControllerConnected(id = 7, name = "A")
        dispatcher.scheduler.runCurrent()

        assertEquals(2, vm.uiState.value.slots.size) // virtual + one physical
    }

    @Test
    fun `onControllerDisconnected drops the slot and unbinds the hub`() = runTest(dispatcher) {
        vm.onControllerConnected(id = 3, name = "Pad")
        vm.onControllerDisconnected(id = 3)
        dispatcher.scheduler.runCurrent()

        val slots = vm.uiState.value.slots
        assertEquals(1, slots.size)
        assertEquals(VIRTUAL_SLOT_ID, slots.first().id)
        verify { hub.unbind("3") }
    }

    @Test
    fun `bindSlot delegates to hub`() {
        vm.bindSlot(slotId = "slot-X", connectionId = "w:1")
        verify { hub.bind("slot-X", "w:1") }
    }

    @Test
    fun `unbindSlot delegates to hub`() {
        vm.unbindSlot(slotId = "slot-X")
        verify { hub.unbind("slot-X") }
    }

    @Test
    fun `hub bindings are reflected as boundConnectionId on slots`() = runTest(dispatcher) {
        val summary = ConnectionSummary(
            id = "w:1", kind = ConnectionKind.WIFI, label = "PC", detail = "1.1.1.1",
            live = ConnectionLive.CONNECTED, boundSlotId = VIRTUAL_SLOT_ID,
        )
        connectionsFlow.value = listOf(summary)
        bindingsFlow.value = mapOf(VIRTUAL_SLOT_ID to "w:1")
        dispatcher.scheduler.runCurrent()

        val virtual = vm.uiState.value.slots.first { it.id == VIRTUAL_SLOT_ID }
        assertEquals("w:1", virtual.boundConnectionId)
        assertEquals(summary, virtual.boundStatus)
    }

    @Test
    fun `unbound slot has null bound fields`() = runTest(dispatcher) {
        connectionsFlow.value = emptyList()
        bindingsFlow.value = emptyMap()
        dispatcher.scheduler.runCurrent()

        val virtual = vm.uiState.value.slots.first { it.id == VIRTUAL_SLOT_ID }
        assertNull(virtual.boundConnectionId)
        assertNull(virtual.boundStatus)
    }
}
