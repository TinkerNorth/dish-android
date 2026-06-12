// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import com.tinkernorth.dish.databinding.OverlayLowPowerChipBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.lowpower.LowPowerManager
import com.tinkernorth.dish.source.lowpower.LowPowerSignal
import com.tinkernorth.dish.source.notification.DishNotifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// rootView must include @layout/overlay_low_power; the host binds it to wire up the dim-screen views.
class GamepadActivityHost(
    private val activity: AppCompatActivity,
    private val rootView: View,
    private val wakeState: WakeStateController,
    private val gamepadRegistry: PhysicalGamepadRegistry,
    private val lowPowerSignal: LowPowerSignal,
) {
    private val window = activity.window
    private val lowPowerTouchGate = LowPowerTouchGate()
    private val lowPowerManager: LowPowerManager
    private val countdownBannerView: View
    private var notificationsAttachment: DishNotifications.Attachment? = null
    private var snackbarAnchorJob: Job? = null
    private var unbufferedJoystickRequested = false
    private var performanceHintsApplied = false

    init {
        val overlay = OverlayLowPowerBinding.bind(rootView)
        val chip = OverlayLowPowerChipBinding.bind(rootView)
        countdownBannerView = chip.llCountdownBanner
        lowPowerManager =
            LowPowerManager(window).apply {
                views =
                    LowPowerManager.Views(
                        llCountdownBanner = chip.llCountdownBanner,
                        tvCountdownSeconds = chip.tvCountdownSeconds,
                        flLowPowerOverlay = overlay.flLowPowerOverlay,
                        tvLowPowerTime = overlay.tvLowPowerTime,
                        tvLowPowerStatus = overlay.tvLowPowerStatus,
                        llStreamingHint = chip.llStreamingHint,
                    )
                activeControllerCount = { wakeState.streamingSlotCount.value }
            }
    }

    fun install(notifications: DishNotifications? = null) {
        lowPowerManager.state
            .onEach { lowPowerSignal.setActive(it == LowPowerManager.State.ACTIVE) }
            .launchIn(activity.lifecycleScope)
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
        if (notifications != null) {
            val attachment = notifications.attach(activity, rootView)
            // Anchor Snackbar above countdown pill during the pre-dim window so banners don't draw over "Low power in Ns".
            snackbarAnchorJob =
                lowPowerManager.state
                    .onEach { state ->
                        attachment.anchorView =
                            if (state == LowPowerManager.State.COUNTDOWN) countdownBannerView else null
                    }.launchIn(activity.lifecycleScope)
            notificationsAttachment = attachment
        }
    }

    fun cancelDimOnStop() {
        lowPowerManager.cancel()
    }

    // Trust the device, not event.source: generic HID adapters report BUTTON_* as SOURCE_KEYBOARD; fallthrough → DPAD_CENTER clicks.
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

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoy =
            isJoystickMotionSource(event.source) ||
                event.deviceId in gamepadRegistry.devices.value
        if (shouldRequestUnbufferedJoystick(isJoy, unbufferedJoystickRequested)) {
            unbufferedJoystickRequested = true
            requestUnbufferedJoystickDispatch(event)
        }
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

    // Read overlayActive BEFORE notifying so a DOWN that dismisses the dim still wins the gate and consumes the rest of the gesture.
    fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val overlayActive = lowPowerManager.state.value == LowPowerManager.State.ACTIVE
        val consume = lowPowerTouchGate.onDispatch(ev.action, overlayActive)
        if (wakeState.shouldKeepScreenOn.value &&
            ev.actionMasked != MotionEvent.ACTION_CANCEL
        ) {
            lowPowerManager.onUserInteraction()
        }
        return consume
    }

    // Release reports on focus loss so no button stays held across shades, calls, or back-button nav.
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) {
            SatelliteNative.releaseAllPhysicalReports()
            return
        }
        if (performanceHintsApplied) return
        performanceHintsApplied = true
        applyPerformanceWindowHints()
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

    // Joystick MotionEvents are vsync-batched by default; unbuffered dispatch delivers each sample as it lands.
    private fun requestUnbufferedJoystickDispatch(event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rootView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)
        } else {
            rootView.requestUnbufferedDispatch(event)
        }
    }

    private fun applyPerformanceWindowHints() {
        val powerManager = activity.getSystemService(PowerManager::class.java)
        // Trade peak clock for a stable one so latency doesn't drift as the SoC heats over a long session.
        if (powerManager?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }
        val display = currentDisplay() ?: return
        val current = display.mode ?: return
        val modeId =
            highestRefreshRateModeId(
                display.supportedModes.map { it.toInfo() },
                current.toInfo(),
            )
        if (modeId != 0) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
        }
    }

    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    private fun Display.Mode.toInfo(): DisplayModeInfo = DisplayModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)
}
