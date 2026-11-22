// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.audio.MicLevelProbe
import com.tinkernorth.dish.source.audio.MicProbeReading
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.audio.SpeakerTestTone
import com.tinkernorth.dish.source.store.BenchTest
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.StickTestHistoryStore
import com.tinkernorth.dish.source.store.TestOutcome
import com.tinkernorth.dish.source.store.TestOutcomeStore
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
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class InputInspectorViewModelTest {
    private val outcomes = TestOutcomeStore()

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
            outcomes,
            nowMs = { dispatcher.scheduler.currentTime },
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
            coEvery { testTone.play(SLOT_ID, PlayoutLane.SPEAKER) } returns false
            val vm = viewModel()
            vm.playTestTone()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeakerTestUi.Unavailable, vm.speakerTest.value)

            coEvery { testTone.play(SLOT_ID, PlayoutLane.SPEAKER) } returns true
            vm.playTestTone()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeakerTestUi.Idle, vm.speakerTest.value)
        }

    @Test
    fun `a test tone while one is playing is ignored`() =
        runTest(dispatcher) {
            coEvery { testTone.play(SLOT_ID, PlayoutLane.SPEAKER) } coAnswers {
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
            coVerify(exactly = 1) { testTone.play(SLOT_ID, PlayoutLane.SPEAKER) }
            assertEquals(SpeakerTestUi.Idle, vm.speakerTest.value)
        }

    @Test
    fun `a trigger rumble bench pulse ends with a zero`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.triggerRumble(left = 100, right = 0, asking = BenchTest.TRIGGER_RUMBLE_LEFT)
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

    // ---- every test asks once it has ended, and its answer carries the time it ended ----

    @Test
    fun `a rumble test asks whether it worked once its buzz has ended`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.buzz(strong = 48000, weak = 0, asking = BenchTest.RUMBLE_STRONG)
            dispatcher.scheduler.runCurrent()
            assertNull(vm.question.value)

            advanceTimeBy(TEST_BUZZ_MS + 1)
            dispatcher.scheduler.runCurrent()

            assertEquals(BenchQuestion(BenchTest.RUMBLE_STRONG, TEST_BUZZ_MS), vm.question.value)
        }

    @Test
    fun `an answer is kept with the time its test ended, not the time it was given`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.buzz(strong = 48000, weak = 0, asking = BenchTest.RUMBLE_STRONG)
            advanceTimeBy(TEST_BUZZ_MS + 1)
            dispatcher.scheduler.runCurrent()
            advanceTimeBy(ANSWERED_LATER_MS)

            vm.answer(worked = true)

            assertEquals(TestOutcome("rumble.strong", "worked", TEST_BUZZ_MS), outcomes.lastOutcomeFor(SLOT_ID))
            assertNull(vm.question.value)
        }

    @Test
    fun `a trigger rumble test stops its motors, then asks by the trigger it ran`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.triggerRumble(left = 0, right = 100, asking = BenchTest.TRIGGER_RUMBLE_RIGHT)
            dispatcher.scheduler.runCurrent()
            assertNull(vm.question.value)

            advanceTimeBy(TEST_BUZZ_MS + 1)
            dispatcher.scheduler.runCurrent()

            verifyOrder {
                feedback.testTriggerRumble(SLOT_ID, 0, 100)
                feedback.testTriggerRumble(SLOT_ID, 0, 0)
            }
            assertEquals(BenchQuestion(BenchTest.TRIGGER_RUMBLE_RIGHT, TEST_BUZZ_MS), vm.question.value)
        }

    @Test
    fun `a light bar cycle gives the bar back, then asks`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.cycleLightbar()
            advanceTimeBy(LIGHTBAR_CYCLE_MS + 1)
            dispatcher.scheduler.runCurrent()

            verifyOrder {
                feedback.testLightbar(SLOT_ID, 0x00, 0x00, 0xFF)
                feedback.endLightbarTest(SLOT_ID)
            }
            assertEquals(BenchQuestion(BenchTest.LIGHTBAR_CYCLE, LIGHTBAR_CYCLE_MS), vm.question.value)
        }

    @Test
    fun `leaving the screen mid-cycle still gives the bar back, and asks nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.cycleLightbar()
            advanceTimeBy(CYCLE_STEP_MS + 1)
            dispatcher.scheduler.runCurrent()

            vm.viewModelScope.cancel()
            dispatcher.scheduler.runCurrent()

            verify(exactly = 1) { feedback.endLightbarTest(SLOT_ID) }
            assertNull(vm.question.value)
        }

    @Test
    fun `a second press while its test runs is ignored`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.cycleLightbar()
            dispatcher.scheduler.runCurrent()
            vm.cycleLightbar()
            advanceTimeBy(LIGHTBAR_CYCLE_MS + 1)
            dispatcher.scheduler.runCurrent()

            verify(exactly = 1) { feedback.testLightbar(SLOT_ID, 0xFF, 0x00, 0x00) }
            verify(exactly = 1) { feedback.endLightbarTest(SLOT_ID) }
        }

    @Test
    fun `a player LED cycle lights each LED in turn, gives them back, then asks`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.cyclePlayerLeds()
            advanceTimeBy(PLAYER_LED_CYCLE_MS + 1)
            dispatcher.scheduler.runCurrent()

            verifyOrder {
                feedback.testPlayerLeds(SLOT_ID, 0x01)
                feedback.testPlayerLeds(SLOT_ID, 0x02)
                feedback.testPlayerLeds(SLOT_ID, 0x04)
                feedback.testPlayerLeds(SLOT_ID, 0x08)
                feedback.testPlayerLeds(SLOT_ID, 0x10)
                feedback.endPlayerLedsTest(SLOT_ID)
            }
            assertEquals(BenchQuestion(BenchTest.PLAYER_LEDS_CYCLE, PLAYER_LED_CYCLE_MS), vm.question.value)
        }

    @Test
    fun `the trigger pulse holds full resistance for two seconds, gives the triggers back, then asks`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.pulseTriggerEffects()
            advanceTimeBy(HOLD_MS - 1)
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { feedback.testTriggerEffects(SLOT_ID, match { it.contentEquals(rigidTriggerBlocks()) }) }
            verify(exactly = 0) { feedback.endTriggerEffectsTest(any()) }
            assertNull(vm.question.value)

            advanceTimeBy(2)
            dispatcher.scheduler.runCurrent()

            verify(exactly = 1) { feedback.endTriggerEffectsTest(SLOT_ID) }
            assertEquals(BenchQuestion(BenchTest.TRIGGER_PULSE, HOLD_MS), vm.question.value)
        }

    @Test
    fun `the lamp test lights the lamp for two seconds, gives it back, then asks`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.micLed(MIC_LED_ON)
            advanceTimeBy(HOLD_MS - 1)
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { feedback.testMicLed(SLOT_ID, MIC_LED_ON) }
            verify(exactly = 0) { feedback.endMicLedTest(any()) }
            assertNull(vm.question.value)

            advanceTimeBy(2)
            dispatcher.scheduler.runCurrent()

            verify(exactly = 1) { feedback.endMicLedTest(SLOT_ID) }
            assertEquals(BenchQuestion(BenchTest.LAMP_ON, HOLD_MS), vm.question.value)
        }

    @Test
    fun `the pulsing lamp test asks about the pulse`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.micLed(MIC_LED_PULSE)
            advanceTimeBy(HOLD_MS + 1)
            dispatcher.scheduler.runCurrent()

            verify(exactly = 1) { feedback.testMicLed(SLOT_ID, MIC_LED_PULSE) }
            assertEquals(BenchQuestion(BenchTest.LAMP_PULSE, HOLD_MS), vm.question.value)
        }

    @Test
    fun `turning the lamp off is not a test`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.micLed(MIC_LED_OFF)
            advanceTimeBy(HOLD_MS + 1)
            dispatcher.scheduler.runCurrent()

            verify(exactly = 0) { feedback.testMicLed(any(), any()) }
            assertNull(vm.question.value)
        }

    @Test
    fun `the speaker tone asks once it has played, with the time it ended`() =
        runTest(dispatcher) {
            coEvery { testTone.play(SLOT_ID, PlayoutLane.SPEAKER) } coAnswers {
                delay(TONE_MS)
                true
            }
            val vm = viewModel()
            vm.playTestTone()
            advanceTimeBy(TONE_MS + 1)
            dispatcher.scheduler.runCurrent()

            assertEquals(BenchQuestion(BenchTest.TONE, TONE_MS), vm.question.value)
        }

    @Test
    fun `the haptics test plays the tone on the haptic lane, then asks`() =
        runTest(dispatcher) {
            coEvery { testTone.play(SLOT_ID, PlayoutLane.HAPTICS) } coAnswers {
                delay(TONE_MS)
                true
            }
            val vm = viewModel()
            vm.playHapticsTone()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeakerTestUi.Playing, vm.hapticsTest.value)

            advanceTimeBy(TONE_MS + 1)
            dispatcher.scheduler.runCurrent()

            assertEquals(SpeakerTestUi.Idle, vm.hapticsTest.value)
            assertEquals(BenchQuestion(BenchTest.HAPTICS, TONE_MS), vm.question.value)
            coVerify(exactly = 0) { testTone.play(any(), PlayoutLane.SPEAKER) }
        }

    @Test
    fun `a refused haptics tone reads unavailable and asks nothing`() =
        runTest(dispatcher) {
            coEvery { testTone.play(SLOT_ID, PlayoutLane.HAPTICS) } returns false
            val vm = viewModel()
            vm.playHapticsTone()
            dispatcher.scheduler.runCurrent()

            assertEquals(SpeakerTestUi.Unavailable, vm.hapticsTest.value)
            assertNull(vm.question.value)
        }

    @Test
    fun `a mic test asks once its eight seconds are up`() =
        runTest(dispatcher) {
            every { micProbe.readings(SLOT_ID) } returns flow { awaitCancellation() }
            val vm = viewModel()
            vm.startMicTest()
            dispatcher.scheduler.runCurrent()
            assertNull(vm.question.value)

            advanceTimeBy(MIC_TEST_MS + 1)
            dispatcher.scheduler.runCurrent()

            assertEquals(BenchQuestion(BenchTest.MIC, MIC_TEST_MS), vm.question.value)
        }

    @Test
    fun `stopping a mic test asks about it`() =
        runTest(dispatcher) {
            every { micProbe.readings(SLOT_ID) } returns flow { awaitCancellation() }
            val vm = viewModel()
            vm.startMicTest()
            advanceTimeBy(STOPPED_AFTER_MS)
            dispatcher.scheduler.runCurrent()

            vm.stopMicTest()

            assertEquals(BenchQuestion(BenchTest.MIC, STOPPED_AFTER_MS), vm.question.value)
        }

    @Test
    fun `a microphone that could not be opened asks nothing`() =
        runTest(dispatcher) {
            every { micProbe.readings(SLOT_ID) } returns flowOf(MicProbeReading.Unavailable)
            val vm = viewModel()
            vm.startMicTest()
            dispatcher.scheduler.runCurrent()

            assertEquals(MicTestUi.Unavailable, vm.micTest.value)
            assertNull(vm.question.value)
        }

    @Test
    fun `a drift test asks once its capture is in`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceTimeBy(CAPTURE_ENDED_MS)

            vm.noteDrift(0.1f, 0.2f, 0.15f)

            assertEquals(BenchQuestion(BenchTest.STICK_DRIFT, CAPTURE_ENDED_MS), vm.question.value)
        }

    @Test
    fun `a range test asks once its capture is in`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceTimeBy(CAPTURE_ENDED_MS)

            vm.noteRange(0.9f, 0.8f, 0.7f, null)

            assertEquals(BenchQuestion(BenchTest.STICK_RANGE, CAPTURE_ENDED_MS), vm.question.value)
        }

    private companion object {
        const val SLOT_ID = "7"
        const val METER_DECAY = 0.04f
        const val MIC_TEST_MS = 8000L
        const val TEST_BUZZ_MS = 400L
        const val TONE_MS = 1000L
        const val HOLD_MS = 2000L
        const val CYCLE_STEP_MS = 400L
        const val ANSWERED_LATER_MS = 5000L
        const val STOPPED_AFTER_MS = 3000L
        const val CAPTURE_ENDED_MS = 3000L

        // The bench's three colors, one 400 ms step each.
        const val LIGHTBAR_CYCLE_MS = 3 * CYCLE_STEP_MS

        // The five player LEDs, one 400 ms step each.
        const val PLAYER_LED_CYCLE_MS = 5 * CYCLE_STEP_MS
    }
}
