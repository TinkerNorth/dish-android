// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.MenuItemCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.input.hidToXusb
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.databinding.ActivityGamepadOverlayBinding
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.sensor.MotionStreamState
import com.tinkernorth.dish.source.sensor.PhoneBatterySource
import com.tinkernorth.dish.source.sensor.PhoneMotionSource
import com.tinkernorth.dish.ui.common.GamepadTouchView
import com.tinkernorth.dish.ui.common.paintConnectionMenuItem
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.showConnectionDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GamepadOverlayActivity :
    BaseInputOverlayActivity(),
    GamepadTouchView.Listener {
    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var capabilityComposer: CapabilityComposer

    private lateinit var binding: ActivityGamepadOverlayBinding

    override fun rootView(): View = binding.root

    override val resendIntervalNs: Long = BaseInputOverlayActivity.RESEND_INTERVAL_NS_DEFAULT

    // @Volatile for main-thread write / resend-thread read.
    @Volatile private var lastReportedState: GamepadTouchView.GamepadState? = null

    private lateinit var motionSource: PhoneMotionSource
    private lateinit var batterySource: PhoneBatterySource

    private var optionsMenu: Menu? = null
    private val currentPaint = MutableStateFlow<OverlayMotionPaint?>(null)
    private var currentMotionState: MotionIndicatorState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamepadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installBaseScaffolding()

        binding.gamepadTouchView.listener = this
        binding.gamepadTouchView.usePlayStation = intent.getBooleanExtra(EXTRA_USE_PS_LAYOUT, false)
        setupDishToolbar(binding.overlayToolbar)
        binding.overlayToolbar.setTitle(R.string.overlay_title_gamepad)
        installRateReadout(
            slotId = VIRTUAL_SLOT_ID,
            motionOn = currentPaint.map(::paintMotionOn),
        ) { binding.overlayToolbar.subtitle = it }

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Supplier re-reads live rotation on each start(); landscape may resolve to ROTATION_90 or ROTATION_270.
        motionSource = PhoneMotionSource(sensorManager, rotationSupplier = ::currentRotation)
        batterySource = PhoneBatterySource(applicationContext)
        repaintFrom(currentMotionPaint())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        hub.connections.map { conns -> conns.firstOrNull { it.id == connectionId } },
                        capabilityComposer.state.map {
                            it[VIRTUAL_SLOT_ID] ?: SlotCapabilities.NONE
                        },
                        motionSource.state,
                    ) { summary, capability, sourceState ->
                        OverlayMotionPaint(summary, capability, sourceState)
                    }.distinctUntilChanged().collect { paint ->
                        applyMotionGate(paint.capability, paint.summary)
                        repaintFrom(paint)
                    }
                } finally {
                    // Stop on collector cancellation (STOP / activity destroy)
                    // so a backgrounded overlay never leaks sensor listeners.
                    motionSource.stop()
                }
            }
        }
    }

    // Resend-thread-only (single-threaded Handler dispatcher).
    private var lastResentSnapshot: GamepadTouchView.GamepadState? = null

    /**
     * Resend-loop tick; pacing is [resendDue] (edge burst, then keepalive).
     * Fires only after the pad was touched once and only at a Connected
     * satellite. Bluetooth is excluded (BT-HID has its own polling
     * discipline and a bounded native dispatch queue).
     */
    override fun resendOneIfReady() {
        val state = lastReportedState ?: return
        val summary = hub.summary(connectionId) ?: return
        if (summary.kind != ConnectionKind.SATELLITE) return
        if (summary.live != LinkState.Connected) return
        // The live state object mutates on the UI thread: copy() is the
        // stable comparison base (a torn read just costs one extra burst).
        val changed = state != lastResentSnapshot
        if (changed) lastResentSnapshot = state.copy()
        if (!resendDue(changed)) return
        sendSatelliteReport(state)
    }

    override fun onResume() {
        super.onResume()
        // Battery is unconditional: it has its own gating on the connection
        // kind inside the satellite send path, and the cost of a slow battery
        // poll is negligible.
        batterySource.start(lifecycleScope) { level, status ->
            satellite.get(connectionId)?.sendBattery(VIRTUAL_SLOT_ID, level, status)
        }
        // Motion gate + indicator repaint stay with the combine collector,
        // which repeatOnLifecycle re-subscribes on STARTED. Nothing to do here.
    }

    override fun onPause() {
        super.onPause()
        batterySource.stop()
    }

    /**
     * One coherent snapshot of the three inputs behind the toolbar indicators
     * and the gyro gate: a single combine, so a paint never mixes emissions
     * (three racing collectors could paint inconsistently during reconnects).
     */
    private data class OverlayMotionPaint(
        val summary: ConnectionSummary?,
        val capability: SlotCapabilities,
        val sourceState: MotionStreamState,
    )

    // Initial paint before the collector starts: the "no gyroscope" state must
    // be ready before onCreateOptionsMenu on phones with no IMU.
    private fun currentMotionPaint(): OverlayMotionPaint =
        OverlayMotionPaint(
            summary = hub.summary(connectionId),
            capability = capabilityComposer.capabilityFor(VIRTUAL_SLOT_ID),
            sourceState = motionSource.state.value,
        )

    // Start/stop the IMU listener so the sensor never runs while the slot is
    // ineligible for motion. Link-liveness is read from the summary, not the
    // capability model, so a reconnect re-arms the gate.
    private fun applyMotionGate(
        capability: SlotCapabilities,
        summary: ConnectionSummary?,
    ) {
        // Motion only carries to a Satellite; a Bluetooth-bound slot must not spin the phone IMU.
        val effective =
            capability.inputOk(Feature.MOTION) &&
                capability.userWants(Feature.MOTION) &&
                summary?.kind == ConnectionKind.SATELLITE &&
                summary.live == LinkState.Connected
        if (effective && !motionSource.isStreaming) {
            motionSource.start { sample, deltaUs ->
                inputRateStore.recordMotionSample(VIRTUAL_SLOT_ID)
                satellite.get(connectionId)?.sendMotion(
                    VIRTUAL_SLOT_ID,
                    sample.gyroX,
                    sample.gyroY,
                    sample.gyroZ,
                    sample.accelX,
                    sample.accelY,
                    sample.accelZ,
                    deltaUs,
                )
            }
        } else if (!effective && motionSource.isStreaming) {
            motionSource.stop()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gamepad_overlay, menu)
        optionsMenu = menu
        currentPaint.value?.let(::paintMenu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_connection_info -> {
                showConnectionDialog(currentPaint.value?.summary)
                true
            }
            R.id.action_motion_info -> {
                showMotionDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun repaintFrom(paint: OverlayMotionPaint) {
        currentPaint.value = paint
        paintMenu(paint)
    }

    private fun paintMenu(paint: OverlayMotionPaint) {
        val menu = optionsMenu ?: return
        paintConnectionMenuItem(menu.findItem(R.id.action_connection_info), paint.summary)
        paintMotionMenuItem(menu.findItem(R.id.action_motion_info), paint)
    }

    private fun paintMotionMenuItem(
        item: MenuItem?,
        paint: OverlayMotionPaint,
    ) {
        val state = motionStateOf(paint)
        currentMotionState = state
        item ?: return
        val active = state == MotionIndicatorState.STREAMING
        item.setIcon(if (active) R.drawable.ic_overlay_motion else R.drawable.ic_overlay_motion_off)
        MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(getColor(state.dotColorRes)))
    }

    private fun motionStateOf(paint: OverlayMotionPaint): MotionIndicatorState =
        motionIndicatorFor(paint.summary, paint.capability, paint.sourceState)

    // The readout's motion entry shows a rate (or the pending glyph) in the states where motion
    // is user-facing on, and Off in the muted indicator states. STALLED/PAUSED count as on: no
    // samples flow there, so the entry reads pending rather than a misleading Off.
    private fun paintMotionOn(paint: OverlayMotionPaint?): Boolean =
        when (paint?.let(::motionStateOf)) {
            MotionIndicatorState.STREAMING,
            MotionIndicatorState.STALLED,
            MotionIndicatorState.PAUSED,
            -> true
            else -> false
        }

    private fun showMotionDialog() {
        val state = currentMotionState ?: currentPaint.value?.let(::motionStateOf) ?: return
        val message =
            buildString {
                append(getString(state.labelRes))
                detailResFor(state)?.let { append("\n\n").append(getString(it)) }
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.overlay_dialog_motion_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    @StringRes
    private fun detailResFor(state: MotionIndicatorState): Int? =
        when (state) {
            MotionIndicatorState.UNAVAILABLE -> R.string.motion_unavailable_detail
            MotionIndicatorState.NOT_FORWARDED -> R.string.motion_not_forwarded_detail
            MotionIndicatorState.STALLED -> R.string.motion_stalled_detail
            MotionIndicatorState.USER_DISABLED -> R.string.motion_user_disabled_detail
            MotionIndicatorState.NO_HOST_SINK -> R.string.motion_no_host_sink_detail
            MotionIndicatorState.BACKEND_BROKEN -> R.string.motion_backend_broken_detail
            else -> null
        }

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        inputRateStore.recordScreenSample()
        // Stored before the connection-status gate so input captured while
        // the session is still Linking is replayed by the resend loop the
        // moment it flips to Connected.
        lastReportedState = state
        val summary = hub.summary(connectionId) ?: return
        if (summary.live != LinkState.Connected) return
        when (summary.kind) {
            ConnectionKind.BLUETOOTH -> {
                val report =
                    btRegistry.buildReport(
                        connectionId,
                        state.buttons,
                        state.hatSwitch,
                        state.leftX,
                        state.leftY,
                        state.rightX,
                        state.rightY,
                        state.leftTrigger,
                        state.rightTrigger,
                    ) ?: return
                btRegistry.sendReport(connectionId, report)
            }
            ConnectionKind.SATELLITE -> sendSatelliteReport(state)
        }
    }

    // The touch view emits HID-layout button bits + a separate hat-switch; the
    // satellite path wants XUSB `wButtons` with the d-pad folded into the low nibble.
    private fun sendSatelliteReport(state: GamepadTouchView.GamepadState) {
        val wButtons = hidToXusb(state.buttons, state.hatSwitch)
        satellite.get(connectionId)?.sendReport(
            VIRTUAL_SLOT_ID,
            wButtons,
            state.leftTrigger,
            state.rightTrigger,
            state.leftX.toInt(),
            state.leftY.toInt(),
            state.rightX.toInt(),
            state.rightY.toInt(),
        )
    }

    companion object {
        // Companion members aren't inherited. Re-export keeps existing
        // qualified call sites compiling.
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID
        const val EXTRA_USE_PS_LAYOUT = "extra_use_ps_layout"
    }
}
