// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import io.mockk.every
import io.mockk.mockk
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
class SetupUsbViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var usb: UsbGamepadManager
    private lateinit var native: PhysicalInputNative
    private val controllers = MutableStateFlow<Map<Int, UsbController>>(emptyMap())
    private lateinit var vm: SetupUsbViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        usb = mockk(relaxed = true)
        native = mockk(relaxed = true)
        every { usb.controllers } returns controllers
        every { native.isKnownFastLaneModel(any(), any()) } returns true
        vm = SetupUsbViewModel(usb, native)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun key(
        vid: Int = VID,
        pid: Int = PID,
    ): Int = (vid shl 16) or (pid and 0xFFFF)

    private fun controller(
        phase: UsbPhase,
        frameworkId: Int? = null,
        syntheticId: Int? = null,
        desired: PathChoice = PathChoice.Standard,
        vid: Int = VID,
        pid: Int = PID,
    ) = UsbController(
        vendorId = vid,
        productId = pid,
        name = "Your controller",
        phase = phase,
        frameworkId = frameworkId,
        syntheticId = syntheticId,
        desired = desired,
    )

    private fun present(
        phase: UsbPhase = UsbPhase.Routed,
        frameworkId: Int? = 50,
        syntheticId: Int? = null,
        desired: PathChoice = PathChoice.Standard,
    ) {
        controllers.value = mapOf(key() to controller(phase, frameworkId, syntheticId, desired))
    }

    @Test
    fun `starts in detecting with nothing plugged in`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            val s = vm.state.value
            assertEquals(SetupUsbViewModel.Stage.DETECTING, s.stage)
            assertFalse(s.present)
        }

    @Test
    fun `a detected controller surfaces its name, code, and verified flag`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            val s = vm.state.value
            assertTrue(s.present)
            assertEquals("Your controller", s.deviceName)
            assertEquals("045E:028E", s.deviceCode)
            assertTrue(s.verified)
        }

    @Test
    fun `an unverified controller reports verified false`() =
        runTest(dispatcher) {
            every { native.isKnownFastLaneModel(any(), any()) } returns false
            present()
            dispatcher.scheduler.runCurrent()
            assertFalse(vm.state.value.verified)
        }

    @Test
    fun `continue only advances to mode once a controller is present`() =
        runTest(dispatcher) {
            vm.continueToMode()
            dispatcher.scheduler.runCurrent()
            assertEquals(SetupUsbViewModel.Stage.DETECTING, vm.state.value.stage)

            present()
            dispatcher.scheduler.runCurrent()
            vm.continueToMode()
            assertEquals(SetupUsbViewModel.Stage.MODE, vm.state.value.stage)
        }

    @Test
    fun `choosing standard records the pref and proceeds with the framework slot id`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            val events = collectEvents()

            vm.chooseStandard()
            dispatcher.scheduler.runCurrent()

            verify { usb.setPathChoice(VID, PID, PathChoice.Standard) }
            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Proceed("50")), events)
        }

    @Test
    fun `choosing direct opens the grant explainer without claiming yet`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            vm.chooseDirect()
            assertEquals(SetupUsbViewModel.Stage.GRANTING, vm.state.value.stage)
            verify(exactly = 0) { usb.setPathChoice(any(), any(), PathChoice.Direct) }
        }

    @Test
    fun `granting direct claims and proceeds with the synthetic slot id`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            // Simulate the FSM: the Direct pick moves the controller into Claiming.
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value = mapOf(key() to controller(UsbPhase.Claiming, desired = PathChoice.Direct))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()
            assertTrue("still working while claiming", vm.state.value.working)

            // Claim succeeds: a synthetic device replaces the framework one.
            controllers.value =
                mapOf(key() to controller(UsbPhase.Direct, syntheticId = -1000, desired = PathChoice.Direct))
            dispatcher.scheduler.runCurrent()

            verify { usb.setPathChoice(VID, PID, PathChoice.Direct) }
            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Proceed("-1000")), events)
            assertFalse(vm.state.value.working)
        }

    @Test
    fun `a refused direct claim falls back to the framework slot id`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            // Permission denied: the FSM reverts desired to Standard while staying routed.
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value =
                    mapOf(key() to controller(UsbPhase.Routed, frameworkId = 50, desired = PathChoice.Standard))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Proceed("50")), events)
        }

    @Test
    fun `back walks grant to mode to detecting and then signals finish`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            vm.continueToMode()
            vm.chooseDirect()
            assertEquals(SetupUsbViewModel.Stage.GRANTING, vm.state.value.stage)

            assertTrue(vm.back())
            assertEquals(SetupUsbViewModel.Stage.MODE, vm.state.value.stage)
            assertTrue(vm.back())
            assertEquals(SetupUsbViewModel.Stage.DETECTING, vm.state.value.stage)
            assertFalse("detecting back is not handled in-flow", vm.back())
        }

    @Test
    fun `unplugging the controller resets back to detecting`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            vm.continueToMode()
            assertEquals(SetupUsbViewModel.Stage.MODE, vm.state.value.stage)

            controllers.value = emptyMap()
            dispatcher.scheduler.runCurrent()

            val s = vm.state.value
            assertEquals(SetupUsbViewModel.Stage.DETECTING, s.stage)
            assertFalse(s.present)
        }

    private fun kotlinx.coroutines.test.TestScope.collectEvents(): List<SetupUsbViewModel.Event> {
        val out = mutableListOf<SetupUsbViewModel.Event>()
        backgroundScope.launch { vm.events.collect { out.add(it) } }
        dispatcher.scheduler.runCurrent()
        return out
    }

    private companion object {
        const val VID = 0x045E
        const val PID = 0x028E
    }
}
