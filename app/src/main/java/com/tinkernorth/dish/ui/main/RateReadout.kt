// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

// The overlay toolbar's subtitle: the touch rate always, the motion rate only on a screen
// that has a motion line (null otherwise).
internal data class RateReadout(
    val touch: RateReading,
    val motion: RateReading?,
)

// motionOn gates the motion line: the source may stream while motion is user-facing off, and
// the readout must agree with the motion indicator, not the raw sample flow.
internal fun rateReadout(
    screenPeakHz: Int,
    gyroHz: Int,
    hasMotion: Boolean,
    motionOn: Boolean,
): RateReadout {
    val touch = if (screenPeakHz > 0) RateReading.PeakHz(screenPeakHz) else RateReading.Pending
    if (!hasMotion) return RateReadout(touch, motion = null)
    val motion =
        when {
            !motionOn -> RateReading.Off
            gyroHz > 0 -> RateReading.LiveHz(gyroHz)
            else -> RateReading.Pending
        }
    return RateReadout(touch, motion)
}
