// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.audio.MicLevelProbe
import com.tinkernorth.dish.source.audio.MicProbeReading
import com.tinkernorth.dish.source.audio.SpeakerTestTone
import com.tinkernorth.dish.source.store.StickTestHistoryStore
import com.tinkernorth.dish.source.store.stickHistoryKeyFor
import com.tinkernorth.dish.source.system.MicPermissionGate
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class InputInspectorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val grantedState = MutableStateFlow(true)
    private val sources = mockk<DiagnosticsSources>(relaxed = true)
    private val micPermission =
        mockk<MicPermissionGate> {
            every { state } returns grantedState
            every { granted } answers { grantedState.value }
            every { refresh() } returns Unit
        }
    private val rumble = mockk<RumbleRouter>(relaxed = true)
    private val feedback = mockk<FeedbackRouter>(relaxed = true)
    private val micProbe = mockk<MicLevelProbe>()
    private val testTone = mockk<SpeakerTestTone>()
    private val stickHistory = mockk<StickTestHistoryStore>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { sources.world } returns flowOf(world())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun world(): DiagnosticsWorld {
        val device = PhysicalGamepadRegistry.Device(id = 7, name = "DualSense", vendorId = 0x054C, productId = 0x0CE6)
        return DiagnosticsWorld(
            devices = mapOf(7 to device),
            virtualName = "Virtual Controller",
            bindings = emptyMap(),
            summaries = emptyList(),
            satellites = emptyMap(),
            rates = emptyMap(),
            batteries = emptyMap(),
            caps = emptyMap(),
            hostFeatures = emptyMap(),
            serverVersions = emptyMap(),
        )
    }

    private fun viewModel(slotId: String = SLOT_ID) =
        InputInspectorViewModel(
            SavedStateHandle(mapOf(InputInspectorViewModel.EXTRA_SLOT_ID to slotId)),
            sources,
            micPermission,
            rumble,
            feedback,
            micProbe,
            testTone,
            stickHistory,
        )

    private fun percent(fraction: Float): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

    private fun TestScope.collectRequests(vm: InputInspectorViewModel): List<Unit> {
        val out = mutableListOf<Unit>()
        backgroundScope.launch { vm.micPermissionRequests.collect { out.add(it) } }
        dispatcher.scheduler.runCurrent()
        return out
    }

    @Test
    fun `a mic test without the grant asks for it and records nothing`() =
        runTest(dispatcher) {
            grantedState.value = false
            val vm = viewModel()
            val requests = collectRequests(vm)

            vm.startMicTest()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, requests.size)
            assertEquals(MicTestUi.Idle, vm.micTest.value)
            verify(exactly = 0) { micProbe.readings(any()) }
        }

    @Test
    fun `mic readings drive a decaying meter and a held peak`() =
        runTest(dispatcher) {
            val loud = MicProbeReading.Level(rms = 0.5f, peak = 0.9f)
            val quiet = MicProbeReading.Level(rms = 0.001f, peak = 0.1f)
            every { micProbe.readings(SLOT_ID) } returns
                flow {
                    emit(loud)
                    emit(quiet)
                    awaitCancellation()
                }
            val vm = viewModel()

            vm.startMicTest()
            dispatcher.scheduler.runCurrent()

            val decayed = max(quiet.meter, loud.meter - METER_DECAY)
            assertEquals(MicTestUi.Running(percent(decayed), percent(0.9f)), vm.micTest.value)
        }

    @Test
    fun `an unavailable reading reads unavailable`() =
        runTest(dispatcher) {
            every { micProbe.readings(SLOT_ID) } returns flowOf(MicProbeReading.Unavailable)
            val vm = viewModel()

            vm.startMicTest()
            dispatcher.scheduler.runCurrent()

            assertEquals(MicTestUi.Unavailable, vm.micTest.value)
        }

    @Test
    fun `a mic test that times out returns to idle`() =
        runTest(dispatcher) {
            every { micProbe.readings(SLOT_ID) } returns
                flow {
                    emit(MicProbeReading.Level(rms = 0.5f, peak = 0.5f))
                    awaitCancellation()
                }
            val vm = viewModel()

            vm.startMicTest()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.micTest.value is MicTestUi.Running)

            advanceTimeBy(MIC_TEST_MS + 1)
            dispatcher.scheduler.runCurrent()
            assertEquals(MicTestUi.Idle, vm.micTest.value)
        }

    @Test
    fun `toggling stops a running test and starts an idle one`() =
        runTest(dispatcher) {
            every { micProbe.readings(SLOT_ID) } returns flow { awaitCancellation() }
            val vm = viewModel()

            vm.toggleMicTest()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.micTest.value is MicTestUi.Running)

            vm.toggleMicTest()
            dispatcher.scheduler.runCurrent()
            assertEquals(MicTestUi.Idle, vm.micTest.value)
            verify(exactly = 1) { micProbe.readings(SLOT_ID) }
        }

    @Test
    fun `a refused test tone reads unavailable and a played one returns to idle`() =
        runTest(dispatcher) {
            coEvery { testTone.play(SLOT_ID) } returns false
            val vm = viewModel()
            vm.playTestTone()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeakerTestUi.Unavailable, vm.speakerTest.value)

            coEvery { testTone.play(SLOT_ID) } returns true
            vm.playTestTone()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeakerTestUi.Idle, vm.speakerTest.value)
        }

    @Test
    fun `a test tone while one is playing is ignored`() =
        runTest(dispatcher) {
            coEvery { testTone.play(SLOT_ID) } coAnswers {
                delay(TONE_MS)
                true
            }
            val vm = viewModel()

            vm.playTestTone()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeakerTestUi.Playing, vm.speakerTest.value)
            vm.playTestTone()
            dispatcher.scheduler.runCurrent()

            advanceTimeBy(TONE_MS + 1)
            dispatcher.scheduler.runCurrent()
            coVerify(exactly = 1) { testTone.play(SLOT_ID) }
            assertEquals(SpeakerTestUi.Idle, vm.speakerTest.value)
        }

    @Test
    fun `a trigger rumble bench pulse ends with a zero`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.triggerRumble(left = 100, right = 0)
            dispatcher.scheduler.runCurrent()
            advanceTimeBy(TEST_BUZZ_MS + 1)
            dispatcher.scheduler.runCurrent()

            verifyOrder {
                feedback.testTriggerRumble(SLOT_ID, 100, 0)
                feedback.testTriggerRumble(SLOT_ID, 0, 0)
            }
        }

    @Test
    fun `a light bar bench cycle paints through the bench and ends by handing the bar back`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.cycleLightbar()
            dispatcher.scheduler.runCurrent()
            advanceTimeBy(LIGHTBAR_CYCLE_MS + 1)
            dispatcher.scheduler.runCurrent()

            verifyOrder {
                feedback.testLightbar(SLOT_ID, 0xFF, 0x00, 0x00)
                feedback.testLightbar(SLOT_ID, 0x00, 0xFF, 0x00)
                feedback.testLightbar(SLOT_ID, 0x00, 0x00, 0xFF)
                feedback.endLightbarTest(SLOT_ID)
            }
            verify(exactly = 0) { feedback.dispatchLightbarToSlot(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a drift note for a controller with facts is keyed by its model`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val job = launch { vm.ui.collect {} }
            dispatcher.scheduler.runCurrent()

            vm.noteDrift(0.1f, 0.2f, 0.15f)

            verify { stickHistory.noteDrift(stickHistoryKeyFor(0x054C, 0x0CE6, "DualSense"), 0.1f, 0.2f, 0.15f, any()) }
            job.cancel()
        }

    @Test
    fun `a range note for a controller with facts is keyed by its model`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val job = launch { vm.ui.collect {} }
            dispatcher.scheduler.runCurrent()

            vm.noteRange(0.9f, 0.8f, 0.7f, null)

            verify { stickHistory.noteRange(stickHistoryKeyFor(0x054C, 0x0CE6, "DualSense"), 0.9f, 0.8f, 0.7f, null, any()) }
            job.cancel()
        }

    @Test
    fun `a drift note for a controller with no facts is dropped`() =
        runTest(dispatcher) {
            val vm = viewModel(slotId = VIRTUAL_SLOT_ID)
            val job = launch { vm.ui.collect {} }
            dispatcher.scheduler.runCurrent()

            vm.noteDrift(0.1f, 0.2f, 0.15f)
            vm.noteRange(0.9f, 0.9f, null, null)

            verify(exactly = 0) { stickHistory.noteDrift(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { stickHistory.noteRange(any(), any(), any(), any(), any(), any()) }
            job.cancel()
        }

    private companion object {
        const val SLOT_ID = "7"
        const val METER_DECAY = 0.04f
        const val MIC_TEST_MS = 8000L
        const val TEST_BUZZ_MS = 400L
        const val TONE_MS = 1000L

        // The bench's three colors, one 400 ms step each.
        const val LIGHTBAR_CYCLE_MS = 3 * 400L
    }
}
