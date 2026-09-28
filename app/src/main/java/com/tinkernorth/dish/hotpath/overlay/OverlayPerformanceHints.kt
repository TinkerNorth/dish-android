// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.view.InputDevice
import android.view.MotionEvent

// Highest-refresh mode at the CURRENT physical resolution; switching resolution would force a reconfigure
// and is not worth it. Returns 0 (no preference) when the current mode already has the best available rate.
internal fun highestRefreshRateModeId(
    modes: List<DisplayModeInfo>,
    current: DisplayModeInfo,
): Int {
    val best =
        modes
            .filter { it.width == current.width && it.height == current.height }
            .maxByOrNull { it.refreshRate }
            ?: return 0
    return if (best.refreshRate > current.refreshRate + REFRESH_RATE_EPSILON_HZ) best.modeId else 0
}

internal fun isJoystickMotionSource(source: Int): Boolean = (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

internal fun shouldRequestUnbufferedJoystick(
    isJoystick: Boolean,
    alreadyRequested: Boolean,
): Boolean = isJoystick && !alreadyRequested

internal fun isGamepadKeySource(source: Int): Boolean =
    (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || isJoystickMotionSource(source)

// Trust the device over event.source: generic HID adapters report BUTTON_* as SOURCE_KEYBOARD, and
// letting such a key fall through turns it into a DPAD_CENTER click on whatever view has focus.
internal fun acceptsGamepadKey(
    source: Int,
    deviceId: Int,
    knownPadIds: Set<Int>,
): Boolean = isGamepadKeySource(source) || deviceId in knownPadIds

// The joystick fold reads any motion from a pad the registry knows, whatever source bits it carries.
// Inline, so the registry lookup costs no object and runs only when the source bits leave it open.
internal inline fun isJoystickEvent(
    source: Int,
    isKnownPad: () -> Boolean,
): Boolean = isJoystickMotionSource(source) || isKnownPad()

// A touch re-arms the inactivity dim only while the screen is being kept on, and a cancelled one never does.
internal fun shouldResetInactivity(
    keepScreenOn: Boolean,
    actionMasked: Int,
): Boolean = keepScreenOn && actionMasked != MotionEvent.ACTION_CANCEL

// Panels report rates as floats (59.96, 60.0); require a clear gap so float noise can't trigger a switch.
private const val REFRESH_RATE_EPSILON_HZ = 1f
