// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-controller dedup for [SatelliteNative.sendBattery].
 *
 * Battery is reported on a slow 30 s cadence plus state-change triggers; a
 * controller sitting at full charge would otherwise burn one packet every
 * poll cycle even though nothing changed. The coalescer remembers the last
 * forwarded `(level, status)` per controller and drops identical follow-ups.
 *
 * Wire-format constants mirror `satellite/src/core/types.h`. The 0xFF level
 * sentinel means "controller exposes status but no percentage" — some
 * Bluetooth pads only report a charging state.
 */
class BatteryCoalescer {
    data class BatterySample(
        val level: Int,
        val status: Int,
    )

    fun interface Emit {
        fun emit(sample: BatterySample)
    }

    private val last = ConcurrentHashMap<Int, BatterySample>()

    /**
     * Attempt to emit [sample] for [controllerIndex]. Returns true if the
     * sample differed from the last emitted one (and was forwarded to [emit]);
     * false if it was coalesced.
     */
    fun publish(
        controllerIndex: Int,
        sample: BatterySample,
        emit: Emit,
    ): Boolean {
        // Reject obviously malformed values defensively. The receiver also
        // rejects these, but failing fast on the sender side keeps the wire
        // clean and surfaces sender bugs locally.
        if (sample.level !in 0..100 && sample.level != LEVEL_UNKNOWN) return false
        if (sample.status !in STATUS_MIN..STATUS_MAX) return false

        val prev = last[controllerIndex]
        if (prev == sample) return false
        last[controllerIndex] = sample
        emit.emit(sample)
        return true
    }

    fun clear(controllerIndex: Int) {
        last.remove(controllerIndex)
    }

    fun clearAll() {
        last.clear()
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

        /** Documented default reporting cadence (`BATTERY_REPORT_INTERVAL_SEC`). */
        const val REPORT_INTERVAL_SECONDS = 30
    }
}
