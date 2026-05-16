// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.network.BatteryCoalescer.BatterySample

/**
 * Pure mapping from an Android [android.os.BatteryState] reading of a physical
 * gamepad to the wire-ready (level, status) [BatterySample], plus the
 * controller-battery-vs-phone-fallback decision.
 *
 * `InputDevice.getBatteryState()` (API 31+) reports a *wireless* pad's own
 * battery: a `capacity` in 0f..1f and a `status` from the `BATTERY_STATUS_*`
 * set. A USB-wired pad, an API < 31 device, or a pad that simply doesn't
 * expose a battery all surface as `isPresent == false` (or a NaN capacity) —
 * in which case the phone is the host and we report *its* battery instead.
 *
 * Kept framework-free + pure (it takes the raw `isPresent` / `capacity` /
 * `status` primitives, not an `InputDevice` or `BatteryState`) so the mapping
 * can be pinned by JVM unit tests — same pattern as [MotionScaling] and
 * [BatteryCoalescer]. The caller ([PhysicalBatterySource]) is responsible for
 * querying [android.view.InputDevice.getBatteryState] and for performing the
 * phone fallback when this returns null.
 */
object PhysicalBatteryMapping {
    // Mirror of android.os.BatteryState.STATUS_* — duplicated as plain ints so
    // the mapping has zero framework dependency and runs in a pure JVM test.
    // These differ from the wire STATUS_* in BatteryCoalescer; the whole job
    // of [statusToWire] is to translate between the two.
    const val ANDROID_STATUS_UNKNOWN = 1
    const val ANDROID_STATUS_CHARGING = 2
    const val ANDROID_STATUS_DISCHARGING = 3
    const val ANDROID_STATUS_NOT_CHARGING = 4
    const val ANDROID_STATUS_FULL = 5

    /**
     * Translate a wireless pad's [android.os.BatteryState] reading to a wire
     * [BatterySample], or null when the pad has no readable battery of its own
     * (so the caller must fall back to the phone battery).
     *
     *  - [isPresent] false → null. A USB-wired pad reports `isPresent == false`
     *    (it has no battery); the phone is the host, so report the phone.
     *  - [capacity] not a valid 0f..1f number (NaN — the documented
     *    "capacity unknown" value) → the pad exposes a battery but no
     *    percentage. We keep the pad's *status* and send the
     *    [BatteryCoalescer.LEVEL_UNKNOWN] sentinel rather than discarding it.
     *  - otherwise → `level = round(capacity * 100)`, status mapped by
     *    [statusToWire].
     */
    fun controllerSample(
        isPresent: Boolean,
        capacity: Float,
        status: Int,
    ): BatterySample? {
        if (!isPresent) return null
        val level =
            if (capacity.isNaN() || capacity < 0f) {
                BatteryCoalescer.LEVEL_UNKNOWN
            } else {
                (capacity * 100f).toInt().coerceIn(0, 100)
            }
        return BatterySample(level, statusToWire(status))
    }

    /**
     * Map an `android.os.BatteryState.STATUS_*` value to the wire
     * `BatteryCoalescer.STATUS_*` set.
     *
     * `STATUS_NOT_CHARGING` (plugged in but held — e.g. by a charge limiter)
     * is reported as discharging: still on mains, but from the player's point
     * of view it reads closest to discharging. This mirrors the identical
     * choice made for the phone battery in [PhoneBatterySource].
     */
    fun statusToWire(status: Int): Int =
        when (status) {
            ANDROID_STATUS_CHARGING -> BatteryCoalescer.STATUS_CHARGING
            ANDROID_STATUS_FULL -> BatteryCoalescer.STATUS_FULL
            ANDROID_STATUS_DISCHARGING,
            ANDROID_STATUS_NOT_CHARGING,
            -> BatteryCoalescer.STATUS_DISCHARGING
            else -> BatteryCoalescer.STATUS_UNKNOWN
        }
}
