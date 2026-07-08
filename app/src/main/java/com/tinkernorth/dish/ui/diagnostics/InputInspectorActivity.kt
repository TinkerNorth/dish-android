// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.databinding.ActivityInputInspectorBinding
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class InputInspectorActivity : BaseGamepadHostActivity() {
    @Inject lateinit var physicalInputNative: PhysicalInputNative

    @Inject lateinit var rumbleRouter: RumbleRouter

    @Inject lateinit var json: Json

    private lateinit var binding: ActivityInputInspectorBinding

    private var deviceId: Int = 0

    private enum class Capture { NONE, DRIFT, RANGE }

    private var capture = Capture.NONE
    private var captureEndsAtMs = 0L
    private val leftSamples = mutableListOf<StickSample>()
    private val rightSamples = mutableListOf<StickSample>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInputInspectorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installGamepadHost(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, 0)
        intent.getStringExtra(EXTRA_DEVICE_NAME)?.let { binding.toolbar.subtitle = it }

        binding.sectionMotion.labelSection.setText(R.string.inspector_section_motion)
        binding.sectionTouch.labelSection.setText(R.string.inspector_section_touch)
        binding.sectionTests.labelSection.setText(R.string.inspector_section_tests)
        binding.sectionRumble.labelSection.setText(R.string.inspector_section_rumble)

        binding.btnDriftTest.setOnClickListener { startCapture(Capture.DRIFT, DRIFT_CAPTURE_MS) }
        binding.btnRangeTest.setOnClickListener { startCapture(Capture.RANGE, RANGE_CAPTURE_MS) }
        binding.btnRumbleWeak.setOnClickListener { buzz(strong = 0, weak = TEST_MAGNITUDE) }
        binding.btnRumbleStrong.setOnClickListener { buzz(strong = TEST_MAGNITUDE, weak = 0) }
        binding.btnRumbleBoth.setOnClickListener { buzz(strong = TEST_MAGNITUDE, weak = TEST_MAGNITUDE) }

        pollWhileStarted()
    }

    override fun onStart() {
        super.onStart()
        // Arms the native motion/touch mirror; onStop disarms it, so the input path pays
        // only the relaxed gate load while this screen is closed.
        physicalInputNative.setInputInspection(true)
    }

    override fun onStop() {
        physicalInputNative.setInputInspection(false)
        super.onStop()
    }

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
        val snapshot = InputSnapshot.parse(json, physicalInputNative.deviceStateJson(deviceId)) ?: return
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

    private fun buzz(
        strong: Int,
        weak: Int,
    ) {
        rumbleRouter.testBuzz(deviceId.toString(), strong, weak, TEST_BUZZ_MS)
    }

    private fun wireGyroToDps(raw: Int): Float = raw * GYRO_DPS_MAX / InputSnapshot.AXIS_SCALE

    private fun wireAccelToG(raw: Int): Float = raw * ACCEL_G_MAX / InputSnapshot.AXIS_SCALE

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        private const val POLL_INTERVAL_MS = 33L
        private const val DRIFT_CAPTURE_MS = 3000L
        private const val RANGE_CAPTURE_MS = 8000L
        private const val TEST_MAGNITUDE = 48000
        private const val TEST_BUZZ_MS = 400

        // Wire scales (contract): gyro full scale 2000 deg/s, accel full scale 4 g.
        private const val GYRO_DPS_MAX = 2000f
        private const val ACCEL_G_MAX = 4f
    }
}
