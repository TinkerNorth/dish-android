// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample

object PhysicalBatteryMapping {
    // Mirror of android.os.BatteryState.STATUS_*, kept framework-free for pure JVM tests.
    const val ANDROID_STATUS_UNKNOWN = 1
    const val ANDROID_STATUS_CHARGING = 2
    const val ANDROID_STATUS_DISCHARGING = 3
    const val ANDROID_STATUS_NOT_CHARGING = 4
    const val ANDROID_STATUS_FULL = 5

    fun controllerSample(
        isPresent: Boolean,
        capacity: Float,
        status: Int,
    ): BatterySample? {
        if (!isPresent) return null
        val level =
            if (capacity.isNaN() || capacity < 0f) {
                BatteryValidator.LEVEL_UNKNOWN
            } else {
                (capacity * 100f).toInt().coerceIn(0, 100)
            }
        return BatterySample(level, statusToWire(status))
    }

    /**
     * A Direct-claimed pad's reading, as the native reader packs it (level shl 8 or status, in
     * the wire's own codes, or -1 for none). The decoder already speaks the wire's codes, so
     * there is nothing to map beyond unpacking; an unknown level with an unknown status is a
     * fault reading the pad did report, and it clears the card rather than leaving a stale
     * number.
     */
    fun directPadSample(packed: Int): BatterySample? {
        if (packed < 0) return null
        val level = (packed ushr 8) and 0xFF
        val status = packed and 0xFF
        if (level == BatteryValidator.LEVEL_UNKNOWN && status == BatteryValidator.STATUS_UNKNOWN) {
            return null
        }
        return BatterySample(level, status)
    }

    fun statusToWire(status: Int): Int =
        when (status) {
            ANDROID_STATUS_CHARGING -> BatteryValidator.STATUS_CHARGING
            ANDROID_STATUS_FULL -> BatteryValidator.STATUS_FULL
            // NOT_CHARGING (plugged-but-held) reported as discharging to match player perception.
            ANDROID_STATUS_DISCHARGING,
            ANDROID_STATUS_NOT_CHARGING,
            -> BatteryValidator.STATUS_DISCHARGING
            else -> BatteryValidator.STATUS_UNKNOWN
        }
}
