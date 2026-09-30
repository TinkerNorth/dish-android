// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample

// The wire's battery vocabulary (contract with satellite/src/core/types.h) and the sample it carries.
object BatteryValidator {
    data class BatterySample(
        val level: Int,
        val status: Int,
    )

    fun interface Emit {
        fun emit(sample: BatterySample)
    }

    const val LEVEL_UNKNOWN = 0xFF

    const val STATUS_UNKNOWN = 0
    const val STATUS_DISCHARGING = 1
    const val STATUS_CHARGING = 2
    const val STATUS_FULL = 3
    const val STATUS_WIRED = 4

    const val STATUS_MIN = STATUS_UNKNOWN
    const val STATUS_MAX = STATUS_WIRED

    const val REPORT_INTERVAL_SECONDS = 30
    const val REPORT_INTERVAL_MS = REPORT_INTERVAL_SECONDS * MS_PER_SECOND
}

private const val MS_PER_SECOND = 1000L
private const val LEVEL_MIN = 0
private const val LEVEL_MAX = 100

// Every sample inside the wire's ranges is forwarded, unchanged ones included: the 30 s heartbeat
// is what heals a dropped packet, so nothing here coalesces.
internal fun publishBatterySample(
    sample: BatterySample,
    emit: BatteryValidator.Emit,
): Boolean {
    val levelIsPercent = sample.level in LEVEL_MIN..LEVEL_MAX
    val levelIsSentinel = sample.level == BatteryValidator.LEVEL_UNKNOWN
    if (!levelIsPercent && !levelIsSentinel) return false
    if (sample.status !in BatteryValidator.STATUS_MIN..BatteryValidator.STATUS_MAX) return false
    emit.emit(sample)
    return true
}
