// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.core.jni.SatelliteNative

class BatteryValidator {
    data class BatterySample(
        val level: Int,
        val status: Int,
    )

    fun interface Emit {
        fun emit(sample: BatterySample)
    }

    fun publish(
        sample: BatterySample,
        emit: Emit,
    ): Boolean {
        if (sample.level !in 0..100 && sample.level != LEVEL_UNKNOWN) return false
        if (sample.status !in STATUS_MIN..STATUS_MAX) return false

        emit.emit(sample)
        return true
    }

    companion object {
        const val LEVEL_UNKNOWN = 0xFF

        const val STATUS_UNKNOWN = 0
        const val STATUS_DISCHARGING = 1
        const val STATUS_CHARGING = 2
        const val STATUS_FULL = 3
        const val STATUS_WIRED = 4

        const val STATUS_MIN = STATUS_UNKNOWN
        const val STATUS_MAX = STATUS_WIRED

        const val REPORT_INTERVAL_SECONDS = 30
    }
}
