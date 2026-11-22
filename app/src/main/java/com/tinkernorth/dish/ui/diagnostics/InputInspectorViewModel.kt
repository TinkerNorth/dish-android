// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.hotpath.input.TRIGGER_EFFECTS_BYTES
import com.tinkernorth.dish.hotpath.input.TRIGGER_EFFECT_BLOCK_BYTES
import com.tinkernorth.dish.source.audio.MicLevelProbe
import com.tinkernorth.dish.source.audio.MicProbeReading
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.audio.SpeakerTestTone
import com.tinkernorth.dish.source.store.BenchTest
import com.tinkernorth.dish.source.store.EndedTest
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.StickTestHistoryStore
import com.tinkernorth.dish.source.store.TestOutcome
import com.tinkernorth.dish.source.store.TestOutcomeStore
import com.tinkernorth.dish.source.store.stickHistoryKeyFor
import com.tinkernorth.dish.source.system.MicPermissionGate
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

private const val TRIGGER_MODE_RIGID = 0x01
private const val TRIGGER_FORCE_MAX = 0xFF

data class FeatureBench(
    val rumble: Boolean,
    val triggerRumble: Boolean,
    val lightbar: Boolean,
    val playerLeds: Boolean,
    val triggerEffects: Boolean,
    val micLed: Boolean,
    val speaker: Boolean,
    val haptics: Boolean,
    val mic: Boolean,
) {
    val anyFeedback: Boolean get() = listOf(rumble, triggerRumble, lightbar, playerLeds, triggerEffects, micLed).any { it }
    val anyAudio: Boolean get() = speaker || haptics || mic

    companion object {
        val NONE =
            FeatureBench(
                rumble = false,
                triggerRumble = false,
                lightbar = false,
                playerLeds = false,
                triggerEffects = false,
                micLed = false,
                speaker = false,
                haptics = false,
                mic = false,
            )

        // The phone's vibrator, speaker and microphone stand in for the on-screen pad; its light
        // surfaces only exist on the skin, so they are not bench-testable from here.
        val VIRTUAL = NONE.copy(rumble = true, speaker = true, mic = true)
    }
}

fun featureBenchFor(
    caps: SlotCapabilities?,
    virtual: Boolean,
): FeatureBench {
    if (virtual) return FeatureBench.VIRTUAL
    caps ?: return FeatureBench.NONE
    return FeatureBench(
        rumble = caps.inputOk(Feature.RUMBLE),
        triggerRumble = caps.inputOk(Feature.TRIGGER_RUMBLE),
        lightbar = caps.inputOk(Feature.LIGHTBAR),
        playerLeds = caps.inputOk(Feature.PLAYER_LEDS),
        triggerEffects = caps.inputOk(Feature.TRIGGER_EFFECTS),
        micLed = caps.inputOk(Feature.MIC),
        speaker = caps.inputOk(Feature.SPEAKER),
        haptics = caps.inputOk(Feature.HAPTIC_AUDIO),
        mic = caps.inputOk(Feature.MIC),
    )
}

// The lamp's on and pulse are worth asking about; turning it off is not a test.
internal fun lampTestFor(state: Int): BenchTest? =
    when (state) {
        MIC_LED_ON -> BenchTest.LAMP_ON
        MIC_LED_PULSE -> BenchTest.LAMP_PULSE
        else -> null
    }

// Left block (0..10) then right (11..21); byte 0 is the DualSense mode, byte 1 the start
// position, byte 2 the force.
internal fun rigidTriggerBlocks(): ByteArray =
    ByteArray(TRIGGER_EFFECTS_BYTES).also { blocks ->
        for (offset in listOf(0, TRIGGER_EFFECT_BLOCK_BYTES)) {
            blocks[offset] = TRIGGER_MODE_RIGID.toByte()
            blocks[offset + 2] = TRIGGER_FORCE_MAX.toByte()
        }
    }

data class InspectorUiState(
    val controller: ControllerDiag?,
    val bench: FeatureBench,
    val micPermissionGranted: Boolean,
    val nowMs: Long = 0L,
)

sealed interface MicTestUi {
    data object Idle : MicTestUi

    data class Running(
        val meterPercent: Int,
        val peakPercent: Int,
    ) : MicTestUi

    data object Unavailable : MicTestUi
}

// The test tone on one lane: the speaker's, or the haptics'.
sealed interface ToneTestUi {
    data object Idle : ToneTestUi

    data object Playing : ToneTestUi

    data object Unavailable : ToneTestUi
}

// What a bench test drives. A lane runs one test at a time.
private enum class BenchLane { RUMBLE, TRIGGER_RUMBLE, LIGHTBAR, PLAYER_LEDS, TRIGGER_EFFECTS, MIC_LAMP, SPEAKER, HAPTICS }

@HiltViewModel
class InputInspectorViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val sources: DiagnosticsSources,
        private val micPermission: MicPermissionGate,
        private val rumble: RumbleRouter,
        private val feedback: FeedbackRouter,
        private val micProbe: MicLevelProbe,
        private val testTone: SpeakerTestTone,
        private val stickHistory: StickTestHistoryStore,
        private val outcomes: TestOutcomeStore,
    ) : ViewModel() {
        val slotId: String = savedState.get<String>(EXTRA_SLOT_ID) ?: VIRTUAL_SLOT_ID
        val deviceId: Int? = slotId.toIntOrNull()
        private val isVirtual = slotId == VIRTUAL_SLOT_ID

        val ui: StateFlow<InspectorUiState> =
            combine(sources.world, micPermission.state) { world, granted ->
                InspectorUiState(
                    controller = controllerDiags(world, sources::touchpadMode).firstOrNull { it.slotId == slotId },
                    bench = featureBenchFor(world.caps[slotId], isVirtual),
                    micPermissionGranted = granted,
                    nowMs = world.nowMs,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(),
                InspectorUiState(controller = null, bench = FeatureBench.NONE, micPermissionGranted = micPermission.granted),
            )

        private val _micTest = MutableStateFlow<MicTestUi>(MicTestUi.Idle)
        val micTest: StateFlow<MicTestUi> = _micTest.asStateFlow()

        private val _speakerTest = MutableStateFlow<ToneTestUi>(ToneTestUi.Idle)
        val speakerTest: StateFlow<ToneTestUi> = _speakerTest.asStateFlow()

        private val _hapticsTest = MutableStateFlow<ToneTestUi>(ToneTestUi.Idle)
        val hapticsTest: StateFlow<ToneTestUi> = _hapticsTest.asStateFlow()

        private val _micPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val micPermissionRequests: SharedFlow<Unit> = _micPermissionRequests.asSharedFlow()

        // The test whose "Did it work?" is waiting for an answer, if any.
        private val _question = MutableStateFlow<EndedTest?>(null)
        val question: StateFlow<EndedTest?> = _question.asStateFlow()

        val lastOutcome: StateFlow<TestOutcome?> =
            outcomes.state
                .map { it[slotId]?.lastOrNull() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), outcomes.lastOutcomeFor(slotId))

        // Each lane's running test. Touched on the main thread only, by the presses and by the tests.
        private val runs = HashMap<BenchLane, Job>()
        private var micJob: Job? = null

        fun answer(worked: Boolean) {
            val asked = _question.value ?: return
            outcomes.note(slotId, asked.test, worked, asked.endedAtMs)
            _question.value = null
        }

        fun buzz(
            strong: Int,
            weak: Int,
            asking: BenchTest,
        ) {
            runOnce(BenchLane.RUMBLE) { buzzThenAsk(strong, weak, asking) }
        }

        fun triggerRumble(
            left: Int,
            right: Int,
            asking: BenchTest,
        ) {
            runOnce(BenchLane.TRIGGER_RUMBLE) { triggerRumbleThenAsk(left, right, asking) }
        }

        fun cycleLightbar() {
            runOnce(BenchLane.LIGHTBAR, ::lightbarCycle)
        }

        fun cyclePlayerLeds() {
            runOnce(BenchLane.PLAYER_LEDS, ::playerLedCycle)
        }

        fun pulseTriggerEffects() {
            runOnce(BenchLane.TRIGGER_EFFECTS, ::triggerPulse)
        }

        fun micLed(state: Int) {
            val asking = lampTestFor(state) ?: return
            runOnce(BenchLane.MIC_LAMP) { lampTest(state, asking) }
        }

        fun playTestTone() {
            runOnce(BenchLane.SPEAKER) { playTone(PlayoutLane.SPEAKER, _speakerTest, BenchTest.TONE) }
        }

        fun playHapticsTone() {
            runOnce(BenchLane.HAPTICS) { playTone(PlayoutLane.HAPTICS, _hapticsTest, BenchTest.HAPTICS) }
        }

        fun toggleMicTest() {
            if (micJob?.isActive == true) stopMicTest() else startMicTest()
        }

        fun startMicTest() {
            if (!micPermission.granted) {
                _micPermissionRequests.tryEmit(Unit)
                return
            }
            micJob?.cancel()
            micJob = viewModelScope.launch { listenThenAsk() }
        }

        fun stopMicTest() {
            val wasListening = _micTest.value is MicTestUi.Running
            micJob?.cancel()
            micJob = null
            _micTest.value = MicTestUi.Idle
            if (wasListening) ask(BenchTest.MIC)
        }

        fun refreshMicPermission() {
            micPermission.refresh()
        }

        fun noteDrift(
            left: Float,
            right: Float,
            suggestedDeadzone: Float,
        ) {
            historyKey()?.let { stickHistory.noteDrift(it, left, right, suggestedDeadzone) }
            ask(BenchTest.STICK_DRIFT)
        }

        fun noteRange(
            reachLeft: Float,
            reachRight: Float,
            circularityLeft: Float?,
            circularityRight: Float?,
        ) {
            historyKey()?.let { stickHistory.noteRange(it, reachLeft, reachRight, circularityLeft, circularityRight) }
            ask(BenchTest.STICK_RANGE)
        }

        // A press while its lane's test still runs is ignored: two runs never share a surface or its
        // give-back, and each run is asked about once.
        private fun runOnce(
            lane: BenchLane,
            test: suspend () -> Unit,
        ) {
            val isRunning = runs[lane]?.isActive == true
            if (isRunning) return
            runs[lane] = viewModelScope.launch { test() }
        }

        private fun ask(test: BenchTest) {
            _question.value = outcomes.ended(test)
        }

        private suspend fun buzzThenAsk(
            strong: Int,
            weak: Int,
            asking: BenchTest,
        ) {
            rumble.testBuzz(slotId, strong, weak, TEST_BUZZ_MS)
            delay(TEST_BUZZ_MS.toLong())
            ask(asking)
        }

        private suspend fun triggerRumbleThenAsk(
            left: Int,
            right: Int,
            asking: BenchTest,
        ) {
            try {
                feedback.testTriggerRumble(slotId, left, right)
                delay(TEST_BUZZ_MS.toLong())
            } finally {
                feedback.testTriggerRumble(slotId, TRIGGERS_STOPPED, TRIGGERS_STOPPED)
            }
            ask(asking)
        }

        private suspend fun lightbarCycle() {
            try {
                for ((r, g, b) in LIGHTBAR_CYCLE) {
                    feedback.testLightbar(slotId, r, g, b)
                    delay(CYCLE_STEP_MS)
                }
            } finally {
                feedback.endLightbarTest(slotId)
            }
            ask(BenchTest.LIGHTBAR_CYCLE)
        }

        private suspend fun playerLedCycle() {
            try {
                for (mask in PLAYER_LED_CYCLE) {
                    feedback.testPlayerLeds(slotId, mask)
                    delay(CYCLE_STEP_MS)
                }
            } finally {
                feedback.endPlayerLedsTest(slotId)
            }
            ask(BenchTest.PLAYER_LEDS_CYCLE)
        }

        private suspend fun triggerPulse() {
            try {
                feedback.testTriggerEffects(slotId, rigidTriggerBlocks())
                delay(STATE_HOLD_MS)
            } finally {
                feedback.endTriggerEffectsTest(slotId)
            }
            ask(BenchTest.TRIGGER_PULSE)
        }

        private suspend fun lampTest(
            state: Int,
            asking: BenchTest,
        ) {
            try {
                feedback.testMicLed(slotId, state)
                delay(STATE_HOLD_MS)
            } finally {
                feedback.endMicLedTest(slotId)
            }
            ask(asking)
        }

        private suspend fun playTone(
            lane: PlayoutLane,
            state: MutableStateFlow<ToneTestUi>,
            asking: BenchTest,
        ) {
            state.value = ToneTestUi.Playing
            val played = testTone.play(slotId, lane)
            state.value = if (played) ToneTestUi.Idle else ToneTestUi.Unavailable
            if (played) ask(asking)
        }

        private suspend fun listenThenAsk() {
            _micTest.value = MicTestUi.Running(meterPercent = 0, peakPercent = 0)
            var meter = 0f
            var peak = 0f
            withTimeoutOrNull(MIC_TEST_MS) {
                micProbe.readings(slotId).collect { reading ->
                    when (reading) {
                        MicProbeReading.Unavailable -> _micTest.value = MicTestUi.Unavailable
                        is MicProbeReading.Level -> {
                            meter = max(reading.meter, meter - METER_DECAY)
                            peak = max(peak, reading.peak)
                            _micTest.value = MicTestUi.Running(percent(meter), percent(peak))
                        }
                    }
                }
            }
            val listenedToTheEnd = _micTest.value is MicTestUi.Running
            if (!listenedToTheEnd) return
            _micTest.value = MicTestUi.Idle
            ask(BenchTest.MIC)
        }

        private fun historyKey(): String? {
            val controller = ui.value.controller ?: return null
            val facts = controller.facts ?: return null
            return stickHistoryKeyFor(facts.vendorId, facts.productId, controller.name)
        }

        private fun percent(fraction: Float): Int = (fraction * PERCENT).roundToInt().coerceIn(0, PERCENT)

        companion object {
            const val EXTRA_SLOT_ID = "extra_slot_id"
            const val TEST_MAGNITUDE = 48000

            private const val TEST_BUZZ_MS = 400
            private const val TRIGGERS_STOPPED = 0
            private const val CYCLE_STEP_MS = 400L

            // The trigger pulse and the mic lamp hold their surface this long before giving it back.
            private const val STATE_HOLD_MS = 2000L
            private const val MIC_TEST_MS = 8000L
            private const val METER_DECAY = 0.04f
            private const val PERCENT = 100

            private val LIGHTBAR_CYCLE =
                listOf(
                    Triple(0xFF, 0x00, 0x00),
                    Triple(0x00, 0xFF, 0x00),
                    Triple(0x00, 0x00, 0xFF),
                )
            private val PLAYER_LED_CYCLE = listOf(0x01, 0x02, 0x04, 0x08, 0x10)
        }
    }
