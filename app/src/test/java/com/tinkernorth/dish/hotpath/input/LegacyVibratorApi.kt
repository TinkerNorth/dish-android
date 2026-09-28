// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

// Marker: below API 31 a pad's only vibrator is the deprecated InputDevice.getVibrator
// (InputDeviceVibrators.kt), and on API 24 and 25 the only vibrate call is the deprecated
// Vibrator.vibrate(long) (RumbleRouter). A test of those branches has to name both; this file is
// the one place that does.
@file:Suppress("DEPRECATION")

package com.tinkernorth.dish.hotpath.input

import android.os.Vibrator
import android.view.InputDevice
import io.mockk.every
import io.mockk.verify

/** Stubs [device]'s pre-31 vibrator as [motor]. */
internal fun stubLegacyVibrator(
    device: InputDevice,
    motor: Vibrator,
) {
    every { device.vibrator } returns motor
}

/** Verifies [vibrator] took the API 24/25 vibrate [calls] times, for [durationMs] when one is given. */
internal fun verifyLegacyVibrate(
    vibrator: Vibrator,
    calls: Int,
    durationMs: Long? = null,
) {
    verify(exactly = calls) { vibrator.vibrate(durationMs ?: any()) }
}
