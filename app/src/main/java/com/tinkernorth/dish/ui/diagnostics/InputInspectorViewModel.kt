// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.audio.MicLevelProbe
import com.tinkernorth.dish.source.audio.MicProbeReading
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.audio.SpeakerTestTone
import com.tinkernorth.dish.source.store.BenchTest
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

private const val TRIGGER_EFFECT_BYTES = 22
private const val TRIGGER_EFFECT_BLOCK_BYTES = 11
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
    val anyAudio: Boolean get() = speaker || mic

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
        haptics = false,
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
    ByteArray(TRIGGER_EFFECT_BYTES).also { blocks ->
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

sealed interface SpeakerTestUi {
    data object Idle : SpeakerTestUi

    data object Playing : SpeakerTestUi

    data object Unavailable : SpeakerTestUi
}

// A test that ended and waits for "Did it work?", with the time it ended: the answer's time.
data class BenchQuestion(
    val test: BenchTest,
    val endedAtMs: Long,
)

@HiltViewModel
class InputInspectorViewModel
    internal constructor(
        savedState: SavedStateHandle,
        private val sources: DiagnosticsSources,
        private val micPermission: MicPermissionGate,
        private val rumble: RumbleRouter,
        private val feedback: FeedbackRouter,
        private val micProbe: MicLevelProbe,
        private val testTone: SpeakerTestTone,
        private val stickHistory: StickTestHistoryStore,
        private val outcomes: TestOutcomeStore,
        private val nowMs: () -> Long,
    ) : ViewModel() {
        @Inject
        constructor(
            savedState: SavedStateHandle,
            sources: DiagnosticsSources,
            micPermission: MicPermissionGate,
            rumble: RumbleRouter,
            feedback: FeedbackRouter,
            micProbe: MicLevelProbe,
            testTone: SpeakerTestTone,
            stickHistory: StickTestHistoryStore,
            outcomes: TestOutcomeStore,
        ) : this(savedState, sources, micPermission, rumble, feedback, micProbe, testTone, stickHistory, outcomes, System::currentTimeMillis)

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

        private val _speakerTest = MutableStateFlow<SpeakerTestUi>(SpeakerTestUi.Idle)
        val speakerTest: StateFlow<SpeakerTestUi> = _speakerTest.asStateFlow()

        private val _hapticsTest = MutableStateFlow<SpeakerTestUi>(SpeakerTestUi.Idle)
        val hapticsTest: StateFlow<SpeakerTestUi> = _hapticsTest.asStateFlow()

        private val _micPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val micPermissionRequests: SharedFlow<Unit> = _micPermissionRequests.asSharedFlow()

        // The test whose "Did it work?" is waiting for an answer, if any.
        private val _question = MutableStateFlow<BenchQuestion?>(null)
        val question: StateFlow<BenchQuestion?> = _question.asStateFlow()

        val lastOutcome: StateFlow<TestOutcome?> =
            outcomes.state
                .map { it[slotId]?.lastOrNull() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), outcomes.lastOutcomeFor(slotId))

        private var micJob: Job? = null
        private var toneJob: Job? = null

        fun buzz(
            strong: Int,
            weak: Int,
            asking: BenchTest,
        ) {
            rumble.testBuzz(slotId, strong, weak, TEST_BUZZ_MS)
            _question.value = BenchQuestion(asking, nowMs())
        }

        fun answer(worked: Boolean) {
            val question = _question.value ?: return
            outcomes.note(slotId, question.test, worked)
            _question.value = null
        }

        @Suppress("UNUSED_PARAMETER")
        fun triggerRumble(
            left: Int,
            right: Int,
            asking: BenchTest,
        ) {
            viewModelScope.launch {
                feedback.testTriggerRumble(slotId, left, right)
                delay(TEST_BUZZ_MS.toLong())
                feedback.testTriggerRumble(slotId, 0, 0)
            }
        }

        fun cycleLightbar() {
            viewModelScope.launch {
                for ((r, g, b) in LIGHTBAR_CYCLE) {
                    feedback.testLightbar(slotId, r, g, b)
                    delay(CYCLE_STEP_MS)
                }
                feedback.endLightbarTest(slotId)
            }
        }

        fun cyclePlayerLeds() {
            viewModelScope.launch {
                for (mask in PLAYER_LED_CYCLE) {
                    feedback.dispatchPlayerLedsToSlot(slotId, mask)
                    delay(CYCLE_STEP_MS)
                }
                feedback.dispatchPlayerLedsToSlot(slotId, 0)
            }
        }

        fun pulseTriggerEffects() {
            _question.value = BenchQuestion(BenchTest.TRIGGER_PULSE, nowMs())
            viewModelScope.launch {
                feedback.dispatchTriggerEffectsToSlot(slotId, rigidTriggerBlocks())
                delay(TRIGGER_EFFECT_HOLD_MS)
                feedback.dispatchTriggerEffectsToSlot(slotId, ByteArray(TRIGGER_EFFECT_BYTES))
            }
        }

        fun micLed(state: Int) {
            feedback.dispatchMicLedToSlot(slotId, state)
            lampTestFor(state)?.let { _question.value = BenchQuestion(it, nowMs()) }
        }

        fun playTestTone() {
            if (toneJob?.isActive == true) return
            toneJob =
                viewModelScope.launch {
                    _speakerTest.value = SpeakerTestUi.Playing
                    val played = testTone.play(slotId, PlayoutLane.SPEAKER)
                    _speakerTest.value = if (played) SpeakerTestUi.Idle else SpeakerTestUi.Unavailable
                    if (played) _question.value = BenchQuestion(BenchTest.TONE, nowMs())
                }
        }

        fun playHapticsTone() = Unit

        fun toggleMicTest() {
            if (micJob?.isActive == true) stopMicTest() else startMicTest()
        }

        fun startMicTest() {
            if (!micPermission.granted) {
                _micPermissionRequests.tryEmit(Unit)
                return
            }
            micJob?.cancel()
            _question.value = BenchQuestion(BenchTest.MIC, nowMs())
            micJob =
                viewModelScope.launch {
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
                    if (_micTest.value is MicTestUi.Running) _micTest.value = MicTestUi.Idle
                }
        }

        fun stopMicTest() {
            micJob?.cancel()
            micJob = null
            _micTest.value = MicTestUi.Idle
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
        }

        fun noteRange(
            reachLeft: Float,
            reachRight: Float,
            circularityLeft: Float?,
            circularityRight: Float?,
        ) {
            historyKey()?.let { stickHistory.noteRange(it, reachLeft, reachRight, circularityLeft, circularityRight) }
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
            private const val CYCLE_STEP_MS = 400L
            private const val TRIGGER_EFFECT_HOLD_MS = 2000L
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
