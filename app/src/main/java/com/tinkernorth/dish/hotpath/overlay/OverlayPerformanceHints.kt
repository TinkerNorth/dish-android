// SPDX-License-Identifier: LGPL-3.0-or-later

@file:Suppress("MatchingDeclarationName")

package com.tinkernorth.dish.hotpath.overlay

import android.view.InputDevice

internal data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

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

// Panels report rates as floats (59.96, 60.0); require a clear gap so float noise can't trigger a switch.
private const val REFRESH_RATE_EPSILON_HZ = 1f
