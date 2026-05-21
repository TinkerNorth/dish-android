// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.input.hidToXusb
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityGamepadOverlayBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.sensor.PhoneBatterySource
import com.tinkernorth.dish.source.sensor.PhoneMotionSource
import com.tinkernorth.dish.ui.common.GamepadTouchView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen landscape activity hosting the on-screen touch gamepad.
 *
 * Bound to a single connection id passed via [EXTRA_CONNECTION_ID]; reports
 * are routed to that connection through either the [BluetoothGamepadRegistry]
 * or the matching [com.tinkernorth.dish.source.connection.SatelliteConnection] in
 * the [SatelliteConnectionManager]. Both owners outlive the host activity so
 * the same session is reused on re-entry.
 *
 * Wake-lock state, dim-after-idle, and physical-gamepad pass-through all
 * live in [GamepadActivityHost] — this activity only owns the on-screen
 * touch gamepad and the status pill at the top of the overlay.
 */
@AndroidEntryPoint
class GamepadOverlayActivity :
    AppCompatActivity(),
    GamepadTouchView.Listener {
    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    private lateinit var binding: ActivityGamepadOverlayBinding
    private lateinit var gamepadHost: GamepadActivityHost
    private var connectionId: String = ""

    /**
     * Phone IMU + battery sources for the on-screen touch controller. Created
     * in [onCreate], streamed only while the activity is resumed (gyro
     * listeners are a measurable battery cost). `motionSource` is a no-op on
     * phones without a gyroscope.
     */
    private lateinit var motionSource: PhoneMotionSource
    private lateinit var batterySource: PhoneBatterySource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamepadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install(notifications) }
        hideSystemBars()

        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()
        binding.gamepadTouchView.listener = this
        binding.gamepadTouchView.usePlayStation = intent.getBooleanExtra(EXTRA_USE_PS_LAYOUT, false)
        binding.dotOverlay.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.colorMuted))
            }
        binding.dotMotion.background =
            GradientDrawable().apply { shape = GradientDrawable.OVAL }
        binding.btnExitGamepad.setOnClickListener { finish() }

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // The IMU axis remap depends on whether `screenOrientation=landscape`
        // resolved to ROTATION_90 or ROTATION_270 on this device. Pass a
        // supplier so PhoneMotionSource re-reads the live rotation each start()
        // rather than baking in a value at onCreate() time.
        motionSource = PhoneMotionSource(sensorManager, rotationSupplier = ::currentRotation)
        // Wire-send only: pushes the virtual controller's MSG_BATTERY to the
        // bound satellite while the overlay is the active input device. The
        // dashboard indicator is fed separately, at process scope, by
        // VirtualBatterySource — so no statusStore is passed here.
        batterySource = PhoneBatterySource(applicationContext)
        // Paint the motion pill once up front: on a phone with no gyroscope
        // this is the only paint that ever runs (start/stop are no-ops), and
        // the "no gyroscope" state must be visible without waiting for resume.
        refreshMotionStatus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect {
                    refreshStatus()
                    // The motion pill depends on connection *kind* (Bluetooth
                    // has no motion channel), so it must repaint when the
                    // bound connection's summary first resolves or changes.
                    refreshMotionStatus()
                }
            }
        }
        // Mid-game connection events: with the SatelliteConnectionManager's
        // SharedFlow buffered, errors emitted from the alive-poll's onDead
        // path can now reach this activity. Previously they were silently
        // dropped because the overlay didn't collect.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect(::handleConnectionEvent)
            }
        }
    }

    /**
     * Mid-game connection events: with [SatelliteConnectionManager]'s
     * SharedFlow buffered, errors emitted from the alive-poll's onDead path
     * can now reach this activity. Previously they were silently dropped —
     * the overlay didn't collect events at all. We can't pop a PIN dialog
     * here (full-screen landscape, no dialog host), so [PairingRequired]
     * also routes through the banner with a "return to Connections" action.
     */
    private fun handleConnectionEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Error ->
                notifications.error(
                    title = event.message,
                    glyph = R.drawable.ic_satellite_off,
                )
            is ConnectionEvent.PairingRequired ->
                notifications.warn(
                    glyph = R.drawable.ic_satellite_off,
                    title = getString(R.string.notif_pairing_needed_title),
                    body =
                        getString(
                            R.string.notif_pairing_needed_body,
                            event.server.name.ifEmpty { event.server.ip },
                        ),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_open),
                        ) { finish() },
                )
        }
    }

    override fun onResume() {
        super.onResume()
        // Stream the phone's gyro/accel + battery to the bound satellite
        // session. `satellite.get(...)` is null for Bluetooth-HID connections,
        // so these emits naturally no-op there — motion forwarding is a
        // satellite-path feature.
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
        batterySource.start(lifecycleScope) { level, status ->
            satellite.get(connectionId)?.sendBattery(VIRTUAL_SLOT_ID, level, status)
        }
        // motionSource is now started (or a no-op if there's no gyroscope) —
        // repaint so the pill reads "streaming" rather than "paused".
        refreshMotionStatus()
    }

    override fun onPause() {
        super.onPause()
        motionSource.stop()
        batterySource.stop()
        // Source stopped to save battery; reflect "paused" (not "unavailable").
        refreshMotionStatus()
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    private fun refreshStatus() {
        val summary = hub.summary(connectionId)
        val connected = summary?.live == LinkState.Connected
        binding.tvOverlayStatus.text =
            when {
                connected -> summary?.label ?: getString(R.string.overlay_status_streaming)
                summary?.live == LinkState.Connecting -> getString(R.string.chip_status_connecting)
                summary == null -> getString(R.string.overlay_status_unknown)
                else -> getString(R.string.overlay_status_not_connected)
            }
        (binding.dotOverlay.background as? GradientDrawable)?.setColor(
            getColor(if (connected) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    /**
     * Repaint the phone-motion pill. There is no motion on/off toggle in this
     * slice, so the state is purely a function of four facts: whether the
     * phone has a gyroscope ([PhoneMotionSource.isAvailable]), whether the
     * source is currently started ([PhoneMotionSource.isStreaming]), whether
     * the bound connection kind can carry `MSG_MOTION` at all (satellite can,
     * Bluetooth-HID cannot), and whether that connection is actually CONNECTED
     * — a "started" source over a down connection is not really streaming, so
     * the pill must not claim it is. The two states that imply motion is *not*
     * leaving the phone also show a one-line explanation, so a limitation is
     * never mistaken for an off switch. See [MotionIndicatorState].
     */
    private fun refreshMotionStatus() {
        // A null summary (connection not resolved yet) is treated as
        // motion-capable but not-yet-connected: the pill reads "paused" until
        // the kind + liveness resolve, then self-corrects on the next refresh.
        val summary = hub.summary(connectionId)
        val carriesMotion = summary?.kind != ConnectionKind.BLUETOOTH
        val connected = summary?.live == LinkState.Connected
        val state =
            MotionIndicatorState.of(
                isAvailable = motionSource.isAvailable,
                isStreaming = motionSource.isStreaming,
                connectionCarriesMotion = carriesMotion,
                connectionConnected = connected,
                isStalled = motionSource.isStalled,
            )
        binding.tvMotionStatus.setText(state.labelRes)
        (binding.dotMotion.background as? GradientDrawable)?.setColor(getColor(state.dotColorRes))
        binding.tvMotionDetail.visibility = if (state.hasDetail) View.VISIBLE else View.GONE
        when (state) {
            MotionIndicatorState.UNAVAILABLE ->
                binding.tvMotionDetail.setText(R.string.motion_unavailable_detail)
            MotionIndicatorState.NOT_FORWARDED ->
                binding.tvMotionDetail.setText(R.string.motion_not_forwarded_detail)
            MotionIndicatorState.STALLED ->
                binding.tvMotionDetail.setText(R.string.motion_stalled_detail)
            else -> Unit
        }
    }

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
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
            ConnectionKind.SATELLITE -> {
                // The touch view emits HID-layout button bits plus a
                // separate hat-switch; the satellite path wants an XUSB
                // wButtons with the d-pad folded back into the low nibble.
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
        }
    }

    /**
     * Live display rotation as a `Surface.ROTATION_*` value, for the IMU axis
     * remap in [PhoneMotionSource]. `Activity.display` is the API 30+ path;
     * `windowManager.defaultDisplay` is the deprecated fallback below R.
     * Defaults to [Surface.ROTATION_0] if no display is attached.
     */
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DISPATCH — forwarded to GamepadActivityHost
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
        const val EXTRA_USE_PS_LAYOUT = "extra_use_ps_layout"
    }
}
