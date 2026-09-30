// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import android.os.Vibrator
import android.view.InputDevice
import io.mockk.every
import io.mockk.verify

/**
 * Stubs [device]'s pre-31 vibrator as [motor].
 *
 * Marker: below API 31 a pad's only vibrator is the deprecated InputDevice.getVibrator
 * (InputDeviceVibrators.kt), so a test of that branch has to stub it; this is the one place that does.
 */
@Suppress("DEPRECATION")
internal fun stubLegacyVibrator(
    device: InputDevice,
    motor: Vibrator,
) {
    every { device.vibrator } returns motor
}

/**
 * Verifies [vibrator] took the API 24/25 vibrate [calls] times, for [durationMs] when one is given.
 *
 * Marker: on API 24 and 25 the only vibrate call is the deprecated Vibrator.vibrate(long)
 * (RumbleRouter), so a test of that branch has to verify it; this is the one place that does.
 */
@Suppress("DEPRECATION")
internal fun verifyLegacyVibrate(
    vibrator: Vibrator,
    calls: Int,
    durationMs: Long? = null,
) {
    verify(exactly = calls) { vibrator.vibrate(durationMs ?: any()) }
}
