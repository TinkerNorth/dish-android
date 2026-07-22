// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.usb.DirectClaimFailure
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
        failure: DirectClaimFailure? = null,
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
        failure = failure,
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
    fun `starts in detecting with an empty controller list`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            val s = vm.state.value
            assertEquals(SetupUsbViewModel.Stage.DETECTING, s.stage)
            assertTrue(s.controllers.isEmpty())
        }

    @Test
    fun `a connected controller appears in the list with its name and code`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            val row =
                vm.state.value.controllers
                    .single()
            assertEquals("Your controller", row.name)
            assertEquals("045E:028E", row.code)
            assertEquals(key(), row.key)
        }

    @Test
    fun `selecting a controller advances to mode and records the verified flag`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            val s = vm.state.value
            assertEquals(SetupUsbViewModel.Stage.MODE, s.stage)
            assertTrue(s.verified)
        }

    @Test
    fun `an unverified controller selects as not verified`() =
        runTest(dispatcher) {
            every { native.isKnownFastLaneModel(any(), any()) } returns false
            present()
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            assertFalse(vm.state.value.verified)
        }

    @Test
    fun `choosing standard records the pref and proceeds with the framework slot id`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
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
            vm.selectController(key())
            vm.chooseDirect()
            assertEquals(SetupUsbViewModel.Stage.GRANTING, vm.state.value.stage)
            verify(exactly = 0) { usb.setPathChoice(any(), any(), PathChoice.Direct) }
        }

    @Test
    fun `granting direct claims and proceeds with the synthetic slot id`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            // Simulate the FSM: the Direct pick moves the controller into Claiming.
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value = mapOf(key() to controller(UsbPhase.Claiming, desired = PathChoice.Direct))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()
            assertTrue("still working while claiming", vm.state.value.working)

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
            vm.selectController(key())
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
    fun `granting direct waits through the permission prompt instead of proceeding on the framework`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            // Permission not granted yet: the FSM stays Routed with desired=Direct while the system
            // prompt is up. The wizard must wait for the claim, not navigate on the Standard slot.
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value =
                    mapOf(key() to controller(UsbPhase.Routed, frameworkId = 50, desired = PathChoice.Direct))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()
            assertTrue("must not proceed on the framework slot before the claim resolves", events.isEmpty())

            controllers.value =
                mapOf(key() to controller(UsbPhase.Direct, syntheticId = -1000, desired = PathChoice.Direct))
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Proceed("-1000")), events)
        }

    @Test
    fun `choosing standard recovers when no framework slot ever resolves`() =
        runTest(dispatcher) {
            present(frameworkId = null)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            val events = collectEvents()

            vm.chooseStandard()
            dispatcher.scheduler.runCurrent()
            assertTrue("waits rather than recovering before the timeout", events.isEmpty())

            dispatcher.scheduler.advanceTimeBy(9_000)
            dispatcher.scheduler.runCurrent()

            verify { usb.setPathChoice(VID, PID, PathChoice.Standard) }
            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Recover(null)), events)
            assertFalse(vm.state.value.working)
        }

    @Test
    fun `choosing standard proceeds once the framework device enumerates`() =
        runTest(dispatcher) {
            present(frameworkId = null)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            val events = collectEvents()

            vm.chooseStandard()
            dispatcher.scheduler.runCurrent()
            assertTrue("waits rather than recovering while the framework is still absent", events.isEmpty())

            controllers.value = mapOf(key() to controller(UsbPhase.Routed, frameworkId = 77))
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Proceed("77")), events)
        }

    @Test
    fun `granting direct recovers when it lands on needs-replug`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value =
                    mapOf(key() to controller(UsbPhase.NeedsReplug, failure = DirectClaimFailure.Dropped))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()

            assertEquals(
                listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Recover(DirectClaimFailure.Dropped)),
                events,
            )
            assertFalse(vm.state.value.working)
        }

    @Test
    fun `granting direct recovers rather than proceeding on a restore-stuck placeholder`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value = mapOf(key() to controller(UsbPhase.RestoreStuck, syntheticId = -2000))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Recover(null)), events)
        }

    @Test
    fun `a second path attempt is ignored while one is in flight`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value = mapOf(key() to controller(UsbPhase.Claiming, desired = PathChoice.Direct))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()
            vm.showPrompt()
            dispatcher.scheduler.runCurrent()

            controllers.value =
                mapOf(key() to controller(UsbPhase.Direct, syntheticId = -1000, desired = PathChoice.Direct))
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf<SetupUsbViewModel.Event>(SetupUsbViewModel.Event.Proceed("-1000")), events)
            verify(exactly = 1) { usb.setPathChoice(VID, PID, PathChoice.Direct) }
        }

    @Test
    fun `backing out during an in-flight wait suppresses navigation`() =
        runTest(dispatcher) {
            present(frameworkId = 50)
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            vm.chooseDirect()
            every { usb.setPathChoice(VID, PID, PathChoice.Direct) } answers {
                controllers.value = mapOf(key() to controller(UsbPhase.Claiming, desired = PathChoice.Direct))
            }
            val events = collectEvents()

            vm.showPrompt()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.back())

            controllers.value =
                mapOf(key() to controller(UsbPhase.Direct, syntheticId = -1000, desired = PathChoice.Direct))
            dispatcher.scheduler.runCurrent()

            assertTrue("a cancelled wait must not navigate", events.isEmpty())
        }

    @Test
    fun `back walks grant to mode to detecting and then signals finish`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            vm.chooseDirect()
            assertEquals(SetupUsbViewModel.Stage.GRANTING, vm.state.value.stage)

            assertTrue(vm.back())
            assertEquals(SetupUsbViewModel.Stage.MODE, vm.state.value.stage)
            assertTrue(vm.back())
            assertEquals(SetupUsbViewModel.Stage.DETECTING, vm.state.value.stage)
            assertFalse("detecting back is not handled in-flow", vm.back())
        }

    @Test
    fun `unplugging the selected controller resets back to detecting`() =
        runTest(dispatcher) {
            present()
            dispatcher.scheduler.runCurrent()
            vm.selectController(key())
            assertEquals(SetupUsbViewModel.Stage.MODE, vm.state.value.stage)

            controllers.value = emptyMap()
            dispatcher.scheduler.runCurrent()

            val s = vm.state.value
            assertEquals(SetupUsbViewModel.Stage.DETECTING, s.stage)
            assertTrue(s.controllers.isEmpty())
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
