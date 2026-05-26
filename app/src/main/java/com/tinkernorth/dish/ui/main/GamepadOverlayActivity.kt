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
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.input.hidToXusb
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

    @Inject lateinit var motionCapability: MotionCapabilityComposer

    private lateinit var binding: ActivityGamepadOverlayBinding

    override fun rootView(): View = binding.root

    override val resendIntervalNs: Long = BaseInputOverlayActivity.RESEND_INTERVAL_NS_DEFAULT

    // @Volatile for main-thread write / Dispatchers.Default read.
    @Volatile private var lastReportedState: GamepadTouchView.GamepadState? = null

    private lateinit var motionSource: PhoneMotionSource
    private lateinit var batterySource: PhoneBatterySource

    private var optionsMenu: Menu? = null
    private var currentPaint: OverlayMotionPaint? = null
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

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Supplier re-reads live rotation on each start(); landscape may resolve to ROTATION_90 or ROTATION_270.
        motionSource = PhoneMotionSource(sensorManager, rotationSupplier = ::currentRotation)
        batterySource = PhoneBatterySource(applicationContext)
        // Surface initial indicator state before the lifecycle collector starts (only runs while STARTED).
        repaintFrom(currentMotionPaint())

        // Single combine so gate + indicator paint read from one snapshot per emission.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        hub.connections.map { conns -> conns.firstOrNull { it.id == connectionId } },
                        motionCapability.state.map {
                            it[VIRTUAL_SLOT_ID] ?: MotionCapability.Off
                        },
                        motionSource.state,
                    ) { summary, capability, sourceState ->
                        OverlayMotionPaint(summary, capability, sourceState)
                    }.distinctUntilChanged().collect { paint ->
                        applyMotionGate(paint.capability)
                        repaintFrom(paint)
                    }
                } finally {
                    // Stop on collector cancellation (STOP / activity destroy)
                    // so a backgrounded overlay never leaks sensor listeners.
                    motionSource.stop()
                }
            }
        }

        // Connection-events collector + resend-loop coroutine live in
        // BaseInputOverlayActivity.installBaseScaffolding(). This activity
        // only owns the gamepad-specific motion/battery combine above.
    }

    /**
     * Resend-loop tick — pulled out of the inline coroutine so the shared
     * 250 Hz pacing lives in [BaseInputOverlayActivity.runResendLoop]. Only
     * fires the wire when (a) the user has touched the pad at least once,
     * and (b) the bound connection is a Connected satellite. Bluetooth is
     * intentionally excluded — BT-HID has its own polling discipline and
     * a bounded native dispatch queue.
     */
    override fun resendOneIfReady() {
        val state = lastReportedState ?: return
        val summary = hub.summary(connectionId) ?: return
        if (summary.kind != ConnectionKind.SATELLITE) return
        if (summary.live != LinkState.Connected) return
        sendSatelliteReport(state)
    }

    override fun onResume() {
        super.onResume()
        // Battery is unconditional — it has its own gating on the connection
        // kind inside the satellite send path, and the cost of a slow battery
        // poll is negligible.
        batterySource.start(lifecycleScope) { level, status ->
            satellite.get(connectionId)?.sendBattery(VIRTUAL_SLOT_ID, level, status)
        }
        // motion source start/stop and indicator paint are driven by the single
        // combine collector in onCreate — repeatOnLifecycle re-subscribes on
        // STARTED so the StateFlow tuple re-emits its current values and the
        // collector handles the gate + repaint. No explicit work needed here.
    }

    override fun onPause() {
        super.onPause()
        batterySource.stop()
        // Indicator repaint is driven by the source-state change inside the
        // combine collector; no explicit refresh here.
    }

    // onStop / dispatchKeyEvent / dispatchGenericMotionEvent / dispatchTouchEvent
    // / onWindowFocusChanged / hideSystemBars / currentRotation —
    // all live in BaseInputOverlayActivity now.

    /**
     * One coherent snapshot of the three inputs that drive both toolbar
     * indicators and the gyro-listener gate. Composed from [hub.connections] +
     * [motionCapability.state] + [motionSource.state] in one combine, so
     * every paint reads from the same emission rather than racing three
     * collectors against three independent reads (which could paint a
     * transient inconsistent state during reconnects). See the combine
     * collector in [onCreate].
     */
    private data class OverlayMotionPaint(
        val summary: ConnectionSummary?,
        val capability: MotionCapability,
        val sourceState: MotionStreamState,
    )

    /**
     * Build a [OverlayMotionPaint] from whatever values the underlying
     * sources have already published. Used for the initial paint in
     * [onCreate] before the lifecycle collector starts — the StateFlow
     * tuple won't emit until something subscribes, but the "no gyroscope"
     * indicator state must be ready before onCreateOptionsMenu fires so the
     * toolbar icon paints right the first time on phones with no IMU.
     */
    private fun currentMotionPaint(): OverlayMotionPaint =
        OverlayMotionPaint(
            summary = hub.summary(connectionId),
            capability = motionCapability.capabilityFor(VIRTUAL_SLOT_ID),
            sourceState = motionSource.state.value,
        )

    /**
     * Listener-gate side-effect — start/stop the IMU listener so the
     * sensor never runs when the slot is ineligible for motion (gyro
     * absent, BT-HID, user toggled off, satellite not connected). Lives
     * inside the combine collector so the gate decision and the indicator
     * paint share one snapshot.
     */
    private fun applyMotionGate(capability: MotionCapability) {
        val effective = capability.effective
        if (effective && !motionSource.isStreaming) {
            motionSource.start { sample, deltaUs ->
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
        currentPaint?.let(::paintMenu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_connection_info -> {
                showConnectionDialog(currentPaint?.summary)
                true
            }
            R.id.action_motion_info -> {
                showMotionDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun repaintFrom(paint: OverlayMotionPaint) {
        currentPaint = paint
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

    private fun motionStateOf(paint: OverlayMotionPaint): MotionIndicatorState {
        val summary = paint.summary
        // A null summary (connection not resolved yet) is treated as motion-capable but
        // not-yet-connected: PAUSED is rendered until the kind + liveness resolve, then the next
        // paint self-corrects.
        val carriesMotion = summary?.kind != ConnectionKind.BLUETOOTH
        val connected = summary?.live == LinkState.Connected
        val cap = paint.capability
        val sourceState = paint.sourceState
        val isAvailable = sourceState != MotionStreamState.Disabled
        val isStreaming =
            sourceState == MotionStreamState.Streaming ||
                sourceState == MotionStreamState.Stalled
        val isStalled = sourceState == MotionStreamState.Stalled
        return MotionIndicatorState.of(
            isAvailable = isAvailable,
            isStreaming = isStreaming,
            connectionCarriesMotion = carriesMotion,
            connectionConnected = connected,
            userEnabled = cap.userEnabled,
            hostHasSinkForType = cap.hostHasSinkForType,
            satelliteBackendOk = cap.satelliteBackendStatus?.backendOk,
            isStalled = isStalled,
        )
    }

    private fun showMotionDialog() {
        val state = currentMotionState ?: currentPaint?.let(::motionStateOf) ?: return
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

    /**
     * Emit one MSG_GAMEPAD_DATA report to the bound satellite. The touch
     * view emits HID-layout button bits plus a separate hat-switch; the
     * satellite path wants an XUSB `wButtons` with the d-pad folded back
     * into the low nibble. Shared between the touch-driven [onGamepadStateChanged]
     * and the periodic resend loop in [onCreate].
     */
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
        /**
         * Re-export of [BaseInputOverlayActivity.EXTRA_CONNECTION_ID] so
         * existing callers (e.g. `MainActivity`) keep their qualified
         * reference. Kotlin companion-object members aren't inherited; this
         * forward keeps the call sites untouched.
         */
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID
        const val EXTRA_USE_PS_LAYOUT = "extra_use_ps_layout"
    }
}
