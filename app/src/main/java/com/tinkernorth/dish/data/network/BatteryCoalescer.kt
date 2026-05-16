// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

/**
 * Battery wire-format constants (mirroring `satellite/src/core/types.h`) plus
 * the per-sample validation gate in front of [SatelliteNative.sendBattery].
 *
 * MSG_BATTERY is a fixed 30 s heartbeat plus an on-connect report: the
 * receiver expects a packet every interval even when the value is unchanged,
 * which is what lets a dropped UDP packet self-heal on the next tick. The
 * 30 s cadence is owned by the poll loops in [PhysicalBatterySource] and
 * [PhoneBatterySource] — [publish] forwards every well-formed sample and
 * drops only malformed ones (it does not coalesce).
 *
 * The 0xFF level sentinel means "controller exposes status but no
 * percentage" — some Bluetooth pads only report a charging state.
 */
class BatteryCoalescer {
    data class BatterySample(
        val level: Int,
        val status: Int,
    )

    fun interface Emit {
        fun emit(sample: BatterySample)
    }

    /**
     * Validate [sample] and forward it via [emit]. Returns true when the
     * sample was well-formed (and emitted), false when it was rejected as
     * malformed.
     *
     * Every well-formed sample is forwarded — there is no dedup, because the
     * receiver expects a fixed 30 s heartbeat (an unchanged value must still
     * reach the wire so a dropped UDP packet self-heals).
     */
    fun publish(
        sample: BatterySample,
        emit: Emit,
    ): Boolean {
        // Reject obviously malformed values defensively. The receiver also
        // rejects these, but failing fast on the sender side keeps the wire
        // clean and surfaces sender bugs locally.
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

        /** Documented default reporting cadence (`BATTERY_REPORT_INTERVAL_SEC`). */
        const val REPORT_INTERVAL_SECONDS = 30
    }
}
