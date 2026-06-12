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
