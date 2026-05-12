// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.util

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import kotlinx.coroutines.launch

/**
 * Per-activity glue for every Dish activity that hosts (or could host) an
 * active streaming session. Owns the three things every activity needs to
 * agree on:
 *
 *  1. Hold the screen on while a slot is bound to a CONNECTED connection —
 *     `FLAG_KEEP_SCREEN_ON` is window-scoped, so each activity has to flip
 *     it on its own window from the shared
 *     [WakeStateController.shouldKeepScreenOn] flag.
 *  2. Present the dim-after-idle overlay through [LowPowerManager], with
 *     its "Bound · N controllers" line reactive to
 *     [WakeStateController.streamingSlotCount].
 *  3. Pass physical gamepad input through to the native pipeline instead
 *     of letting Android's fallback-action machinery turn `BUTTON_*`
 *     events into `KEYCODE_DPAD_CENTER` and click views.
 *
 * State (the [LowPowerManager], the [LowPowerTouchGate]) is per-activity;
 * the underlying controllers are process-scoped `@Singleton`s.
 *
 * ### Usage
 *
 * ```
 * private lateinit var gamepadHost: GamepadActivityHost
 *
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     binding = ActivityFooBinding.inflate(layoutInflater)
 *     setContentView(binding.root)
 *     gamepadHost = GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
 *     gamepadHost.install()
 *     // ... activity-specific UI wiring ...
 * }
 *
 * override fun onStop() {
 *     super.onStop()
 *     gamepadHost.cancelDimOnStop()
 * }
 *
 * override fun dispatchKeyEvent(event: KeyEvent): Boolean =
 *     gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
 *
 * override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
 *     gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
 *
 * override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
 *     gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)
 *
 * override fun onWindowFocusChanged(hasFocus: Boolean) {
 *     super.onWindowFocusChanged(hasFocus)
 *     gamepadHost.onWindowFocusChanged(hasFocus)
 * }
 * ```
 *
 * [rootView] must be the inflated root of a layout that includes
 * `@layout/overlay_low_power`; the host binds it here to wire up the
 * dim-screen views.
 */
class GamepadActivityHost(
    private val activity: AppCompatActivity,
    rootView: View,
    private val wakeState: WakeStateController,
    private val gamepadRegistry: PhysicalGamepadRegistry,
) {
    private val window = activity.window
    private val lowPowerTouchGate = LowPowerTouchGate()
    private val lowPowerManager: LowPowerManager

    init {
        // Construct LowPowerManager eagerly so `dispatchTouchEvent` and
        // `applyScreenOn` can read/poke its state immediately — the
        // collectors in `install()` are what push values into it.
        val bindings = OverlayLowPowerBinding.bind(rootView)
        lowPowerManager =
            LowPowerManager(window).apply {
                views =
                    LowPowerManager.Views(
                        llCountdownBanner = bindings.llCountdownBanner,
                        tvCountdownSeconds = bindings.tvCountdownSeconds,
                        flLowPowerOverlay = bindings.flLowPowerOverlay,
                        tvLowPowerTime = bindings.tvLowPowerTime,
                        tvLowPowerStatus = bindings.tvLowPowerStatus,
                    )
                activeControllerCount = { wakeState.streamingSlotCount.value }
            }
    }

    /**
     * Start the two lifecycle-scoped collectors:
     *   - [WakeStateController.shouldKeepScreenOn] → toggle
     *     `FLAG_KEEP_SCREEN_ON` and forward to [LowPowerManager.onLockStateChanged].
     *   - [WakeStateController.streamingSlotCount] → call
     *     [LowPowerManager.refreshStatus] so the dim line tracks
     *     bind/unbind without waiting for the 15-second clock tick.
     *
     * Both gates on `STARTED` so the collectors only run while the host
     * activity is visible.
     */
    fun install() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeState.shouldKeepScreenOn.collect(::applyScreenOn)
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeState.streamingSlotCount.collect { lowPowerManager.refreshStatus() }
            }
        }
    }

    /** Tear the dim-screen state machine down on `onStop`. */
    fun cancelDimOnStop() {
        lowPowerManager.cancel()
    }

    /**
     * Returns true if the host wants to consume the event. Callers should
     * fall through to `super.dispatchKeyEvent(event)` only when this returns
     * false.
     *
     * Trust the device, not the event's source bits. Generic HID joystick
     * adapters dispatch button events with `src=SOURCE_KEYBOARD` even when
     * the device itself exposes `SOURCE_JOYSTICK`; letting those fall
     * through lets Android's fallback-action pipeline turn `BUTTON_*` into
     * `KEYCODE_DPAD_CENTER` and click whatever button is focused.
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isKnownGamepad = event.deviceId in gamepadRegistry.devices.value
        if (!isGamepadSource(event.source) && !isKnownGamepad) return false
        SatelliteNative.processGamepadKeyEvent(
            event.deviceId,
            event.source,
            event.action,
            event.keyCode,
        )
        return true
    }

    /** Same shape as [dispatchKeyEvent] but for joystick motion. */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoy =
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                event.deviceId in gamepadRegistry.devices.value
        return isJoy &&
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
    }

    /**
     * Returns true if the touch should be swallowed (dim-screen overlay was
     * the target). Caller falls through to `super.dispatchTouchEvent(ev)`
     * otherwise so normal touch handling continues.
     *
     * Reads the overlay-active state *before* notifying the manager so a
     * DOWN that dismisses the dim still wins the gate (and consumes the
     * rest of the gesture, so the underlying button doesn't get a click).
     */
    fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val overlayActive = lowPowerManager.state == LowPowerManager.State.ACTIVE
        val consume = lowPowerTouchGate.onDispatch(ev.action, overlayActive)
        if (ev.action == MotionEvent.ACTION_DOWN && wakeState.shouldKeepScreenOn.value) {
            lowPowerManager.onUserInteraction()
        }
        return consume
    }

    /**
     * Release every per-device gamepad report when the window loses focus,
     * so no button stays held server-side across notification shades,
     * incoming calls, or back-button navigation.
     */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) SatelliteNative.releaseAllPhysicalReports()
    }

    private fun applyScreenOn(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lowPowerManager.onLockStateChanged(active)
    }

    private fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}
