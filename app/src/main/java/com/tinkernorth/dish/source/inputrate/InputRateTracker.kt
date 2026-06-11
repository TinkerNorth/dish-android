// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import kotlin.math.roundToInt

// Turns a monotonic event counter into a rate. lastHz is the most recent window that measured
// anything and holds through idle windows, so a stopped stream (overlay closed, gyro gated off)
// keeps displaying its last measurement instead of reverting to pending. peakHz is the highest
// window seen; event-driven sources (framework input, touch) read it as the delivery-rate
// approximation built from the bursts the user actually produced.
class InputRateTracker {
    private var prevCount = -1L
    private var prevAtMs = 0L

    var lastHz: Int = 0
        private set

    var peakHz: Int = 0
        private set

    fun update(
        count: Long,
        nowMs: Long,
    ) {
        val hadBaseline = prevCount >= 0
        val deltaCount = count - prevCount
        val deltaMs = nowMs - prevAtMs
        prevCount = count
        prevAtMs = nowMs
        if (!hadBaseline) return
        val hz = windowRateHz(deltaCount, deltaMs)
        if (hz <= 0) return
        lastHz = hz
        if (hz > peakHz) peakHz = hz
    }

    // Drops the snapshot so the next update only re-seeds; the window spanning a paused span
    // (low power) would otherwise read artificially low. Held values survive the pause.
    fun rebaseline() {
        prevCount = -1L
    }
}

// Quantised to 5 Hz steps so a steady source renders one stable number instead of flickering
// around the true rate by the ±1-count jitter of a 500 ms window.
internal fun windowRateHz(
    deltaCount: Long,
    deltaMs: Long,
): Int {
    if (deltaCount <= 0L || deltaMs <= 0L) return 0
    val hz = deltaCount * MILLIS_PER_SEC / deltaMs
    return (hz / RATE_STEP_HZ).roundToInt() * RATE_STEP_HZ
}

private const val RATE_STEP_HZ = 5
private const val MILLIS_PER_SEC = 1000.0
