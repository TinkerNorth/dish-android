// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.system.BluetoothPermissionState
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import io.mockk.every
import io.mockk.mockk
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
class SetupBluetoothControllerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var permission: BluetoothPermissionStateObserver
    private lateinit var registry: PhysicalGamepadRegistry
    private lateinit var vm: SetupBluetoothControllerViewModel

    private val perm = MutableStateFlow(BluetoothPermissionState.SATISFIED)
    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        permission = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        every { permission.state } returns perm
        every { registry.devices } returns devices
        vm = SetupBluetoothControllerViewModel(permission, registry)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun device(
        id: Int,
        name: String,
        transport: Transport = Transport.Bluetooth,
        disconnectingTimeLeftSec: Int? = null,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = name,
        transport = transport,
        disconnectingTimeLeftSec = disconnectingTimeLeftSec,
    )

    @Test
    fun `only connected bluetooth controllers are listed`() =
        runTest(dispatcher) {
            devices.value =
                mapOf(
                    1 to device(1, "BT Pad"),
                    2 to device(2, "USB Pad", transport = Transport.Usb),
                    3 to device(3, "Leaving", disconnectingTimeLeftSec = 3),
                )
            dispatcher.scheduler.runCurrent()
            val rows = vm.state.value.controllers
            assertEquals(1, rows.size)
            assertEquals("BT Pad", rows.single().name)
            assertEquals("1", rows.single().slotId)
        }

    @Test
    fun `permission missing reflects the observer state`() =
        runTest(dispatcher) {
            perm.value = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.state.value.permissionMissing)

            perm.value = BluetoothPermissionState.SATISFIED
            dispatcher.scheduler.runCurrent()
            assertFalse(vm.state.value.permissionMissing)
        }

    @Test
    fun `tapping a controller proceeds with its slot id`() =
        runTest(dispatcher) {
            val seen = collectEvents()
            vm.onControllerTapped(SetupBluetoothControllerViewModel.Controller(slotId = "7", name = "BT Pad"))
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf<SetupBluetoothControllerViewModel.Event>(SetupBluetoothControllerViewModel.Event.Proceed("7")), seen)
        }

    private fun kotlinx.coroutines.test.TestScope.collectEvents(): List<SetupBluetoothControllerViewModel.Event> {
        val out = mutableListOf<SetupBluetoothControllerViewModel.Event>()
        backgroundScope.launch { vm.events.collect { out.add(it) } }
        dispatcher.scheduler.runCurrent()
        return out
    }
}
