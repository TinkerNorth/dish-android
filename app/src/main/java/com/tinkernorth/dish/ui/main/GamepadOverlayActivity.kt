// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.databinding.ActivityGamepadOverlayBinding
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.hidToXusb
import com.tinkernorth.dish.ui.common.GamepadTouchView
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.LowPowerTouchGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen landscape activity hosting the on-screen touch gamepad.
 *
 * Bound to a single connection id passed via [EXTRA_CONNECTION_ID]; reports
 * are routed to that connection through either the [BluetoothGamepadRegistry]
 * or the matching [com.tinkernorth.dish.data.network.SatelliteConnection] in
 * the [SatelliteConnectionManager]. Both owners outlive the host activity so
 * the same session is reused on re-entry.
 */
@AndroidEntryPoint
class GamepadOverlayActivity :
    AppCompatActivity(),
    GamepadTouchView.Listener {
    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var wakeState: WakeStateController

    private lateinit var binding: ActivityGamepadOverlayBinding
    private lateinit var lowPowerBinding: OverlayLowPowerBinding
    private lateinit var lowPowerManager: LowPowerManager
    private val lowPowerTouchGate = LowPowerTouchGate()
    private var connectionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamepadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lowPowerBinding = OverlayLowPowerBinding.bind(binding.root)
        hideSystemBars()
        setupPower()

        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()
        binding.gamepadTouchView.listener = this
        binding.gamepadTouchView.usePlayStation = intent.getBooleanExtra(EXTRA_USE_PS_LAYOUT, false)
        binding.dotOverlay.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.colorMuted))
            }
        binding.btnExitGamepad.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect { refreshStatus() }
            }
        }
        // FLAG_KEEP_SCREEN_ON is per-window, so each activity has to flip it
        // itself; the actual decision lives in the process-scoped
        // WakeStateController and is shared with MainActivity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeState.shouldKeepScreenOn.collect(::applyScreenOn)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Kill the dim-screen state machine cleanly; the next onStart re-derives
        // it from the wake-state collector.
        lowPowerManager.cancel()
    }

    private fun setupPower() {
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views =
            LowPowerManager.Views(
                llCountdownBanner = lowPowerBinding.llCountdownBanner,
                tvCountdownSeconds = lowPowerBinding.tvCountdownSeconds,
                flLowPowerOverlay = lowPowerBinding.flLowPowerOverlay,
                tvLowPowerTime = lowPowerBinding.tvLowPowerTime,
                tvLowPowerStatus = lowPowerBinding.tvLowPowerStatus,
            )
        lowPowerManager.activeControllerCount = { wakeState.streamingSlotCount.value }
    }

    private fun applyScreenOn(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lowPowerManager.onLockStateChanged(active)
    }

    private fun refreshStatus() {
        val summary = hub.summary(connectionId)
        val connected = summary?.live == ConnectionLive.CONNECTED
        binding.tvOverlayStatus.text =
            when {
                connected -> summary?.label ?: "Streaming"
                summary?.live == ConnectionLive.CONNECTING -> "Connecting…"
                summary == null -> "Unknown connection"
                else -> "Not connected"
            }
        (binding.dotOverlay.background as? GradientDrawable)?.setColor(
            getColor(if (connected) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        val summary = hub.summary(connectionId) ?: return
        if (summary.live != ConnectionLive.CONNECTED) return
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
    //  PHYSICAL INPUT DISPATCH
    //
    //  Mirrors MainActivity: while the overlay is foreground we still need to
    //  feed physical gamepad events into the native pipeline so they reach
    //  the bound satellite/BT slot instead of being absorbed by Android's
    //  default focus-navigation handling (A = click, BACK = finish, dpad =
    //  view traversal). The per-device bindings consulted by
    //  SatelliteNative.process* live in the process-scoped
    //  PhysicalSlotBindingObserver, so they stay valid across the
    //  MainActivity → overlay handoff.
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isGamepadSource(event.source) &&
            SatelliteNative.processGamepadKeyEvent(
                event.deviceId,
                event.source,
                event.action,
                event.keyCode,
            )
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            SatelliteNative.processGamepadMotionEvent(
                event.deviceId,
                event.source,
                event.action,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                event.getAxisValue(MotionEvent.AXIS_RX),
                event.getAxisValue(MotionEvent.AXIS_RY),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            )
        ) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Same shape as MainActivity: snapshot overlay-active state first so a
        // DOWN that dismisses the dim wins the gate and the rest of the gesture
        // is swallowed before the gamepad view sees it as a button press.
        val overlayActive = lowPowerManager.state == LowPowerManager.State.ACTIVE
        val consume = lowPowerTouchGate.onDispatch(ev.action, overlayActive)
        if (ev.action == MotionEvent.ACTION_DOWN && wakeState.shouldKeepScreenOn.value) {
            lowPowerManager.onUserInteraction()
        }
        return if (consume) true else super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Symmetric with MainActivity: zero every bound device on focus loss
        // so no button stays held server-side if the overlay is interrupted
        // (notification shade, incoming call, etc.).
        if (!hasFocus) SatelliteNative.releaseAllPhysicalReports()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
        const val EXTRA_USE_PS_LAYOUT = "extra_use_ps_layout"
    }
}
