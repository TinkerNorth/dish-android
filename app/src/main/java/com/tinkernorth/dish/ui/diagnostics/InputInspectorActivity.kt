// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.databinding.ActivityInputInspectorBinding
import com.tinkernorth.dish.databinding.InspectorTestRowBinding
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.diagnostics.InputInspectorViewModel.Companion.TEST_MAGNITUDE
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.math.roundToInt

@Suppress("TooManyFunctions")
@AndroidEntryPoint
class InputInspectorActivity : BaseGamepadHostActivity() {
    @Inject lateinit var physicalInputNative: PhysicalInputNative

    @Inject lateinit var json: Json

    private val viewModel: InputInspectorViewModel by viewModels()
    private lateinit var binding: ActivityInputInspectorBinding
    private val nav by lazy { DishNavigator(this) }

    override val holdsScreenAwake: Boolean get() = true

    private val deviceId: Int? get() = viewModel.deviceId

    private enum class Capture { NONE, DRIFT, RANGE }

    private var capture = Capture.NONE
    private var captureEndsAtMs = 0L
    private val leftSamples = mutableListOf<StickSample>()
    private val rightSamples = mutableListOf<StickSample>()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.refreshMicPermission()
            when {
                granted -> viewModel.startMicTest()
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> showMicPermissionBlocked()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityInputInspectorBinding::inflate)
        setupDishToolbar(binding.toolbar)
        intent.getStringExtra(EXTRA_DEVICE_NAME)?.let { binding.toolbar.subtitle = it }

        binding.sectionHost.labelSection.setText(R.string.inspector_section_host)
        binding.sectionDevice.labelSection.setText(R.string.diagnostics_section_device)
        binding.sectionInput.labelSection.setText(R.string.inspector_section_input)
        binding.btnOpenBinding.setOnClickListener {
            nav.toBindingInspector(
                viewModel.slotId,
                binding.toolbar.subtitle
                    ?.toString()
                    .orEmpty(),
            )
        }
        binding.sectionMotion.labelSection.setText(R.string.inspector_section_motion)
        binding.sectionTouch.labelSection.setText(R.string.inspector_section_touch)
        binding.sectionTests.labelSection.setText(R.string.inspector_section_tests)
        binding.sectionFeedback.labelSection.setText(R.string.inspector_section_feedback)
        binding.sectionAudio.labelSection.setText(R.string.inspector_section_audio)

        binding.btnDriftTest.setOnClickListener { startCapture(Capture.DRIFT, DRIFT_CAPTURE_MS) }
        binding.btnRangeTest.setOnClickListener { startCapture(Capture.RANGE, RANGE_CAPTURE_MS) }
        wireBench()

        observe(viewModel.ui, ::renderUi)
        observe(viewModel.micTest, ::renderMicTest)
        observe(viewModel.speakerTest, ::renderSpeakerTest)
        observe(viewModel.micPermissionRequests) { requestMicPermission() }

        if (deviceId != null) {
            pollWhileStarted()
        } else {
            binding.containerLiveInput.visibility = View.GONE
        }
    }

    private fun <T> observe(
        flow: Flow<T>,
        render: (T) -> Unit,
    ) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Arms the native motion/touch mirror; onStop disarms it, so the input path pays
        // only the relaxed gate load while this screen is closed.
        if (deviceId != null) physicalInputNative.setInputInspection(true)
    }

    override fun onStop() {
        if (deviceId != null) physicalInputNative.setInputInspection(false)
        super.onStop()
    }

    // ── Host + bench ────────────────────────────────────────────────────────

    private fun renderUi(state: InspectorUiState) {
        val controller = state.controller
        controller?.let { binding.toolbar.subtitle = it.name }
        binding.tvHostLine.text = diagKv(R.string.diagnostics_host, hostValue(controller?.host))
        setLines(binding.tvSlotLine, controller?.host?.let { boundSlotLines(it) }.orEmpty())
        setLines(binding.tvBatteryLine, listOfNotNull(controller?.battery?.let { diagKv(R.string.setup_cap_battery, batteryValue(it)) }))
        binding.tvHostHint.visibility = if (controller?.host == null) View.VISIBLE else View.GONE
        binding.btnOpenBinding.visibility = if (controller?.host == null) View.GONE else View.VISIBLE
        val deviceLines = controller?.let { padDeviceLines(it, state.nowMs) }.orEmpty()
        binding.containerDevice.renderLines(deviceLines)
        setVisible(binding.sectionDevice.root, deviceLines.isNotEmpty())

        val bench = state.bench
        setVisible(binding.rowRumble.root, bench.rumble)
        setVisible(binding.rowTriggerRumble.root, bench.triggerRumble)
        setVisible(binding.rowLightbar.root, bench.lightbar)
        setVisible(binding.rowPlayerLeds.root, bench.playerLeds)
        setVisible(binding.rowTriggerEffects.root, bench.triggerEffects)
        setVisible(binding.rowMicLed.root, bench.micLed)
        setVisible(binding.tvFeedbackEmpty, !bench.anyFeedback)
        setVisible(binding.rowSpeaker.root, bench.speaker)
        setVisible(binding.rowMic.root, bench.mic)
        setVisible(binding.tvAudioEmpty, !bench.anyAudio)
    }

    private fun setLines(
        view: TextView,
        lines: List<String>,
    ) {
        view.text = lines.joinToString("\n")
        setVisible(view, lines.isNotEmpty())
    }

    private fun setVisible(
        view: View,
        visible: Boolean,
    ) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun wireBench() {
        bindTestRow(
            binding.rowRumble,
            R.string.setup_cap_rumble,
            R.string.inspector_rumble_weak to { viewModel.buzz(strong = 0, weak = TEST_MAGNITUDE) },
            R.string.inspector_rumble_strong to { viewModel.buzz(strong = TEST_MAGNITUDE, weak = 0) },
            R.string.inspector_rumble_both to { viewModel.buzz(strong = TEST_MAGNITUDE, weak = TEST_MAGNITUDE) },
        )
        bindTestRow(
            binding.rowTriggerRumble,
            R.string.setup_cap_trigger_rumble,
            R.string.inspector_trigger_left to { viewModel.triggerRumble(left = TEST_MAGNITUDE, right = 0) },
            R.string.inspector_trigger_right to { viewModel.triggerRumble(left = 0, right = TEST_MAGNITUDE) },
            R.string.inspector_rumble_both to { viewModel.triggerRumble(left = TEST_MAGNITUDE, right = TEST_MAGNITUDE) },
        )
        bindTestRow(binding.rowLightbar, R.string.setup_cap_lightbar, R.string.inspector_cycle to { viewModel.cycleLightbar() })
        bindTestRow(binding.rowPlayerLeds, R.string.setup_cap_player_leds, R.string.inspector_cycle to { viewModel.cyclePlayerLeds() })
        bindTestRow(
            binding.rowTriggerEffects,
            R.string.setup_cap_trigger_effects,
            R.string.inspector_pulse_triggers to { viewModel.pulseTriggerEffects() },
        )
        bindTestRow(
            binding.rowMicLed,
            R.string.inspector_mic_lamp,
            R.string.inspector_lamp_on to { viewModel.micLed(MIC_LED_ON) },
            R.string.inspector_lamp_pulse to { viewModel.micLed(MIC_LED_PULSE) },
            R.string.setup_cap_off to { viewModel.micLed(MIC_LED_OFF) },
        )
        binding.rowSpeaker.tvAudioLabel.setText(R.string.setup_cap_speaker)
        binding.rowSpeaker.btnAudioAction.setText(R.string.inspector_play_tone)
        binding.rowSpeaker.btnAudioAction.setOnClickListener { viewModel.playTestTone() }
        binding.rowMic.tvAudioLabel.setText(R.string.setup_cap_mic)
        binding.rowMic.btnAudioAction.setText(R.string.inspector_mic_test)
        binding.rowMic.btnAudioAction.setOnClickListener { viewModel.toggleMicTest() }
    }

    private fun bindTestRow(
        row: InspectorTestRowBinding,
        @StringRes label: Int,
        vararg actions: Pair<Int, () -> Unit>,
    ) {
        row.tvTestLabel.setText(label)
        listOf(row.btnTestA, row.btnTestB, row.btnTestC).forEachIndexed { index, button ->
            val action = actions.getOrNull(index)
            setVisible(button, action != null)
            action?.let { (text, onClick) ->
                button.setText(text)
                button.setOnClickListener { onClick() }
            }
        }
    }

    private fun renderMicTest(ui: MicTestUi) {
        val row = binding.rowMic
        when (ui) {
            MicTestUi.Idle -> {
                row.btnAudioAction.setText(R.string.inspector_mic_test)
                row.barAudioLevel.visibility = View.GONE
                row.tvAudioStatus.text = ""
            }
            is MicTestUi.Running -> {
                row.btnAudioAction.setText(R.string.inspector_mic_stop)
                row.barAudioLevel.visibility = View.VISIBLE
                row.barAudioLevel.progress = ui.meterPercent
                row.tvAudioStatus.text =
                    if (ui.peakPercent == 0) {
                        getString(R.string.inspector_mic_listening)
                    } else {
                        getString(R.string.inspector_mic_level, ui.meterPercent, ui.peakPercent)
                    }
            }
            MicTestUi.Unavailable -> {
                row.btnAudioAction.setText(R.string.inspector_mic_test)
                row.barAudioLevel.visibility = View.GONE
                row.tvAudioStatus.setText(R.string.inspector_mic_unavailable)
            }
        }
    }

    private fun renderSpeakerTest(ui: SpeakerTestUi) {
        val row = binding.rowSpeaker
        row.btnAudioAction.isEnabled = ui != SpeakerTestUi.Playing
        row.tvAudioStatus.text =
            when (ui) {
                SpeakerTestUi.Idle -> ""
                SpeakerTestUi.Playing -> getString(R.string.inspector_tone_playing)
                SpeakerTestUi.Unavailable -> getString(R.string.inspector_tone_unavailable)
            }
    }

    // ── Microphone permission ───────────────────────────────────────────────

    private fun requestMicPermission() {
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.binding_mic_permission_title)
            .setMessage(R.string.binding_mic_permission_rationale)
            .setPositiveButton(R.string.action_grant) { _, _ ->
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }.setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showMicPermissionBlocked() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.binding_mic_permission_title)
            .setMessage(R.string.binding_mic_permission_blocked)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            }.setNegativeButton(R.string.action_close, null)
            .show()
    }

    // ── Live input ──────────────────────────────────────────────────────────

    private fun pollWhileStarted() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    tick()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun tick() {
        val id = deviceId ?: return
        val snapshot = InputSnapshot.parse(json, physicalInputNative.deviceStateJson(id)) ?: return
        renderLive(snapshot)
        if (capture != Capture.NONE) tickCapture(snapshot)
    }

    private fun renderLive(s: InputSnapshot) {
        binding.plotLeftStick.update(s.leftSample())
        binding.plotRightStick.update(s.rightSample())
        binding.tvRawValues.text = getString(R.string.inspector_values, s.lx, s.ly, s.rx, s.ry, s.lt, s.rt)
        binding.barLeftTrigger.progress = s.lt
        binding.barRightTrigger.progress = s.rt

        val pressed = WireButton.entries.filter { (s.buttons and it.bit) != 0 }.joinToString(" · ") { it.label }
        binding.tvButtons.text =
            getString(R.string.inspector_pressed, pressed.ifEmpty { getString(R.string.inspector_none) })

        if (s.motionValid) {
            binding.tvGyro.text =
                getString(R.string.inspector_gyro_value, wireGyroToDps(s.gx), wireGyroToDps(s.gy), wireGyroToDps(s.gz))
            binding.tvAccel.text =
                getString(R.string.inspector_accel_value, wireAccelToG(s.ax), wireAccelToG(s.ay), wireAccelToG(s.az))
        } else {
            binding.tvGyro.text = getString(R.string.inspector_motion_missing)
            binding.tvAccel.text = ""
        }

        if (s.touchValid) {
            binding.plotTouch.update(s)
            binding.tvTouchHint.text = ""
        } else {
            binding.tvTouchHint.text = getString(R.string.inspector_touch_missing)
        }
    }

    private fun startCapture(
        kind: Capture,
        durationMs: Long,
    ) {
        capture = kind
        captureEndsAtMs = SystemClock.elapsedRealtime() + durationMs
        leftSamples.clear()
        rightSamples.clear()
        binding.tvTestResult.text = ""
        if (kind == Capture.RANGE) {
            binding.plotLeftStick.startTrail()
            binding.plotRightStick.startTrail()
        }
    }

    private fun tickCapture(s: InputSnapshot) {
        leftSamples += s.leftSample()
        rightSamples += s.rightSample()
        val leftMs = captureEndsAtMs - SystemClock.elapsedRealtime()
        if (leftMs > 0) {
            binding.tvTestResult.text = getString(R.string.inspector_capturing, (leftMs / 1000 + 1).toInt())
            return
        }
        val kind = capture
        capture = Capture.NONE
        binding.plotLeftStick.stopTrail()
        binding.plotRightStick.stopTrail()
        binding.tvTestResult.text = if (kind == Capture.DRIFT) driftResult() else rangeResult()
    }

    private fun driftResult(): String {
        val driftL = StickHealth.drift(leftSamples)
        val driftR = StickHealth.drift(rightSamples)
        val suggested = StickHealth.suggestedDeadzone(maxOf(driftL, driftR))
        viewModel.noteDrift(driftL, driftR, suggested)
        return getString(
            R.string.inspector_drift_result,
            percent(driftL),
            percent(driftR),
            percent(suggested),
        )
    }

    private fun rangeResult(): String {
        val left = StickHealth.envelope(leftSamples)
        val right = StickHealth.envelope(rightSamples)
        binding.plotLeftStick.clearTrail()
        binding.plotRightStick.clearTrail()
        viewModel.noteRange(worstReach(left), worstReach(right), left.circularityError, right.circularityError)
        return getString(
            R.string.inspector_range_result,
            percent(worstReach(left)),
            percent(worstReach(right)),
            left.circularityError?.let { percent(it) } ?: getString(R.string.inspector_na),
            right.circularityError?.let { percent(it) } ?: getString(R.string.inspector_na),
        )
    }

    // The rail the stick struggles to reach is the one that matters in game.
    private fun worstReach(e: StickHealth.Envelope): Float = minOf(-e.minX, e.maxX, -e.minY, e.maxY).coerceAtLeast(0f)

    private fun percent(fraction: Float): String = getString(R.string.inspector_percent, (fraction * 100).roundToInt())

    private fun wireGyroToDps(raw: Int): Float = raw * GYRO_DPS_MAX / InputSnapshot.AXIS_SCALE

    private fun wireAccelToG(raw: Int): Float = raw * ACCEL_G_MAX / InputSnapshot.AXIS_SCALE

    companion object {
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        private const val POLL_INTERVAL_MS = 33L
        private const val DRIFT_CAPTURE_MS = 3000L
        private const val RANGE_CAPTURE_MS = 8000L

        // Wire scales (contract): gyro full scale 2000 deg/s, accel full scale 4 g.
        private const val GYRO_DPS_MAX = 2000f
        private const val ACCEL_G_MAX = 4f
    }
}
