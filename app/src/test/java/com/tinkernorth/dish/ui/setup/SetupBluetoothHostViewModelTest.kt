// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.input.GamepadProfile
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.system.BluetoothPermissionState
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupBluetoothHostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var registry: BluetoothGamepadRegistry
    private lateinit var permission: BluetoothPermissionStateObserver
    private lateinit var store: ConnectionStore
    private lateinit var hub: ConnectionCoordinator
    private lateinit var capabilityComposer: CapabilityComposer
    private lateinit var motion: PhoneMotionAvailability
    private lateinit var vm: SetupBluetoothHostViewModel

    private val remembered = MutableStateFlow<List<RememberedBt>>(emptyList())
    private val perm = MutableStateFlow(BluetoothPermissionState.SATISFIED)
    private val states = MutableStateFlow<Map<String, BluetoothGamepadRegistry.SlotState>>(emptyMap())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        registry = mockk(relaxed = true)
        permission = mockk(relaxed = true)
        store = mockk(relaxed = true)
        hub = mockk(relaxed = true)
        capabilityComposer = mockk(relaxed = true)
        motion = mockk(relaxed = true)
        every { registry.states } returns states
        every { permission.state } returns perm
        every { store.rememberedBtFlow } returns remembered
        every { motion.hasGyro } returns true
        every { hub.bind(any(), any(), any()) } returns true
        every { capabilityComposer.capabilityForCandidate(any(), any(), any(), any(), any()) } returns SlotCapabilities.NONE
        vm = SetupBluetoothHostViewModel(registry, permission, store, hub, capabilityComposer, motion)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun slot(
        isConnected: Boolean,
        isRegistered: Boolean = false,
    ) = mockk<BluetoothGamepadRegistry.SlotState> {
        every { connected } returns isConnected
        every { registered } returns isRegistered
        every { connectedName } returns null
    }

    @Test
    fun `starts on the pc picker with the remembered hosts`() =
        runTest(dispatcher) {
            remembered.value = listOf(rememberedBt("bt:OLD", "Old PC", "AA:OLD", "XBOX"))
            dispatcher.scheduler.runCurrent()
            val s = vm.state.value
            assertEquals(SetupBluetoothHostViewModel.Stage.PICK_PC, s.stage)
            assertEquals(1, s.hosts.size)
            assertEquals("Old PC", s.hosts.first().name)
        }

    @Test
    fun `selecting a host with bluetooth missing routes to the permission gate`() =
        runTest(dispatcher) {
            perm.value = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
            dispatcher.scheduler.runCurrent()
            vm.onHostSelected(
                SetupBluetoothHostViewModel.HostRow("bt:X", "PC", "AA", GamepadProfile.XBOX),
            )
            assertEquals(SetupBluetoothHostViewModel.Stage.PERMISSION, vm.state.value.stage)
            verify(exactly = 0) { registry.start(any(), any(), any()) }
        }

    @Test
    fun `pairing a new device with permission granted goes to the type picker`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.onPairNewDevice()
            assertEquals(SetupBluetoothHostViewModel.Stage.PICK_TYPE, vm.state.value.stage)
        }

    @Test
    fun `choosing a type starts advertising and asks for the discoverable prompt`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            val events = collectEvents()
            vm.onTypeChosen(GamepadProfile.PLAYSTATION)
            dispatcher.scheduler.runCurrent()

            assertEquals(SetupBluetoothHostViewModel.Stage.ADVERTISING, vm.state.value.stage)
            verify { registry.start(any(), GamepadProfile.PLAYSTATION, null) }
            assertTrue(events.contains(SetupBluetoothHostViewModel.Event.RequestDiscoverable))
        }

    @Test
    fun `a host already bonded before we start does not carry the flow forward`() =
        runTest(dispatcher) {
            // An old host is already live; pairing a brand-new device must wait for
            // OUR session's host, not proceed on the pre-existing one.
            states.value = mapOf("bt:OLD" to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            vm.onPairNewDevice()
            val events = collectEvents()

            vm.onTypeChosen(GamepadProfile.XBOX)
            dispatcher.scheduler.runCurrent()
            assertFalse(events.any { it is SetupBluetoothHostViewModel.Event.Done })

            // Our new host bonds under its own key; now we bind the input to it and
            // finish, carrying that key forward.
            states.value =
                mapOf("bt:OLD" to slot(isConnected = true), "bt:NEW" to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()

            verify { hub.bind("42", "bt:NEW", CONTROLLER_TYPE_XBOX) }
            assertTrue(events.any { it is SetupBluetoothHostViewModel.Event.Done })
        }

    @Test
    fun `back walks advertising to type to pc, then defers to the activity`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.onPairNewDevice()
            vm.onTypeChosen(GamepadProfile.XBOX)
            dispatcher.scheduler.runCurrent()
            assertEquals(SetupBluetoothHostViewModel.Stage.ADVERTISING, vm.state.value.stage)

            assertTrue(vm.back())
            assertEquals(SetupBluetoothHostViewModel.Stage.PICK_TYPE, vm.state.value.stage)
            assertTrue(vm.back())
            assertEquals(SetupBluetoothHostViewModel.Stage.PICK_PC, vm.state.value.stage)
            assertFalse(vm.back())
        }

    @Test
    fun `a bond that lands after the controller left finishes unbound`() =
        runTest(dispatcher) {
            every { hub.bind(any(), any(), any()) } returns false
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            val events = collectEvents()
            vm.onTypeChosen(GamepadProfile.XBOX)
            dispatcher.scheduler.runCurrent()

            states.value = mapOf("bt:NEW" to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()

            val done = events.filterIsInstance<SetupBluetoothHostViewModel.Event.Done>().single()
            assertFalse(done.bound)
        }

    @Test
    fun `a second connected emission does not finish twice`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            val events = collectEvents()
            vm.onTypeChosen(GamepadProfile.XBOX)
            dispatcher.scheduler.runCurrent()

            states.value = mapOf("bt:NEW" to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()
            states.value = mapOf("bt:NEW" to slot(isConnected = true), "bt:OTHER" to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()

            assertEquals(1, events.count { it is SetupBluetoothHostViewModel.Event.Done })
            verify(exactly = 1) { hub.bind(any(), any(), any()) }
        }

    @Test
    fun `a connected emission under our own transient key finishes with that key`() =
        runTest(dispatcher) {
            val startedId = slot<String>()
            justRun { registry.start(capture(startedId), any(), any()) }
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            val events = collectEvents()
            vm.onTypeChosen(GamepadProfile.XBOX)
            dispatcher.scheduler.runCurrent()
            val transientId = startedId.captured

            states.value = mapOf(transientId to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()

            verify { hub.bind("42", transientId, CONTROLLER_TYPE_XBOX) }
            assertTrue(events.any { it is SetupBluetoothHostViewModel.Event.Done })
        }

    @Test
    fun `a remembered host already registered when we start finishes once its own key connects`() =
        runTest(dispatcher) {
            val hostId = BluetoothGamepadRegistry.idFor(REMEMBERED_MAC)
            states.value = mapOf(hostId to slot(isConnected = false, isRegistered = true))
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            val events = collectEvents()
            vm.onHostSelected(SetupBluetoothHostViewModel.HostRow(hostId, "Old PC", REMEMBERED_MAC, GamepadProfile.XBOX))
            dispatcher.scheduler.runCurrent()

            states.value = mapOf(hostId to slot(isConnected = true, isRegistered = true))
            dispatcher.scheduler.runCurrent()

            verify { hub.bind("42", hostId, CONTROLLER_TYPE_XBOX) }
            assertTrue(events.any { it is SetupBluetoothHostViewModel.Event.Done })
        }

    @Test
    fun `a registered but not yet connected session does not finish`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            val events = collectEvents()
            vm.onTypeChosen(GamepadProfile.XBOX)
            dispatcher.scheduler.runCurrent()

            states.value = mapOf("bt:NEW" to slot(isConnected = false, isRegistered = true))
            dispatcher.scheduler.runCurrent()

            assertFalse(events.any { it is SetupBluetoothHostViewModel.Event.Done })
            verify(exactly = 0) { hub.bind(any(), any(), any()) }
        }

    @Test
    fun `a PlayStation bond binds as the PlayStation type`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.bindArgs("42")
            vm.onTypeChosen(GamepadProfile.PLAYSTATION)
            dispatcher.scheduler.runCurrent()

            states.value = mapOf("bt:NEW" to slot(isConnected = true))
            dispatcher.scheduler.runCurrent()

            verify { hub.bind("42", "bt:NEW", CONTROLLER_TYPE_PLAYSTATION) }
        }

    @Test
    fun `a grant on the permission step advances to the type picker`() =
        runTest(dispatcher) {
            perm.value = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
            dispatcher.scheduler.runCurrent()
            vm.onPairNewDevice()
            assertEquals(SetupBluetoothHostViewModel.Stage.PERMISSION, vm.state.value.stage)

            perm.value = BluetoothPermissionState.SATISFIED
            dispatcher.scheduler.runCurrent()
            vm.onPermissionResult()

            assertEquals(SetupBluetoothHostViewModel.Stage.PICK_TYPE, vm.state.value.stage)
        }

    @Test
    fun `a grant that is still missing keeps the permission step`() =
        runTest(dispatcher) {
            perm.value = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
            dispatcher.scheduler.runCurrent()
            vm.onPairNewDevice()
            vm.onPermissionResult()
            assertEquals(SetupBluetoothHostViewModel.Stage.PERMISSION, vm.state.value.stage)
        }

    @Test
    fun `a grant landing while not on the permission step leaves the stage alone`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.onPermissionResult()
            assertEquals(SetupBluetoothHostViewModel.Stage.PICK_PC, vm.state.value.stage)
        }

    @Test
    fun `the discoverable flag mirrors the prompt result`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            vm.onDiscoverableResult(true)
            assertTrue(vm.state.value.discoverable)
            vm.onDiscoverableResult(false)
            assertFalse(vm.state.value.discoverable)
        }

    @Test
    fun `a remembered host with a legacy enum-name profile resolves its profile`() =
        runTest(dispatcher) {
            remembered.value =
                listOf(
                    rememberedBt("bt:A", "Legacy PC", "AA:A", "PLAYSTATION"),
                    rememberedBt("bt:B", "Named PC", "AA:B", "PlayStation"),
                    rememberedBt("bt:C", "Odd PC", "AA:C", "unknown"),
                )
            dispatcher.scheduler.runCurrent()
            val profiles =
                vm.state.value.hosts
                    .map { it.profile }
            assertEquals(listOf(GamepadProfile.PLAYSTATION, GamepadProfile.PLAYSTATION, GamepadProfile.XBOX), profiles)
        }

    private fun rememberedBt(
        rowId: String,
        rowName: String,
        rowMac: String,
        profile: String,
    ) = mockk<RememberedBt> {
        every { id } returns rowId
        every { name } returns rowName
        every { mac } returns rowMac
        every { profileName } returns profile
    }

    private fun kotlinx.coroutines.test.TestScope.collectEvents(): List<SetupBluetoothHostViewModel.Event> {
        val out = mutableListOf<SetupBluetoothHostViewModel.Event>()
        backgroundScope.launch { vm.events.collect { out.add(it) } }
        dispatcher.scheduler.runCurrent()
        return out
    }

    private companion object {
        const val REMEMBERED_MAC = "AA:BB:CC:DD:EE:FF"
    }
}
