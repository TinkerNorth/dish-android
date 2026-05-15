// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reports the phone's own battery as the "controller battery" while it acts
 * as an on-screen touch controller. Closes the Android half of roadmap
 * Task 1.2.
 *
 * Polls the sticky `ACTION_BATTERY_CHANGED` intent every
 * [BatteryCoalescer.REPORT_INTERVAL_SECONDS] (30 s, matching the receiver's
 * `BATTERY_REPORT_INTERVAL_SEC`) plus once immediately on [start]. The
 * [BatteryCoalescer] drops the 30 s heartbeat when nothing changed, so a
 * phone sitting at a steady charge costs ~one packet per state transition.
 */
class PhoneBatterySource(
    private val context: Context,
    private val coalescer: BatteryCoalescer = BatteryCoalescer(),
) {
    /** Invoked with a wire-ready (level, status) pair when the value changes. */
    fun interface Emit {
        fun emit(level: Int, status: Int)
    }

    private var job: Job? = null

    /** Begin the 30 s poll loop on [scope]. Safe to call twice (re-arms). */
    fun start(scope: CoroutineScope, emit: Emit) {
        stop()
        job =
            scope.launch {
                while (isActive) {
                    readBattery()?.let { sample ->
                        coalescer.publish(VIRTUAL_CONTROLLER, sample) { s ->
                            emit.emit(s.level, s.status)
                        }
                    }
                    delay(BatteryCoalescer.REPORT_INTERVAL_SECONDS * 1000L)
                }
            }
    }

    /** Stop polling. Safe to call when not started. */
    fun stop() {
        job?.cancel()
        job = null
        coalescer.clearAll()
    }

    /** Read the current battery level + charging state, or null if unavailable. */
    private fun readBattery(): BatteryCoalescer.BatterySample? {
        val intent: Intent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null

        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val level =
            if (rawLevel >= 0 && scale > 0) {
                (rawLevel * 100 / scale).coerceIn(0, 100)
            } else {
                BatteryCoalescer.LEVEL_UNKNOWN
            }

        val status =
            when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> BatteryCoalescer.STATUS_CHARGING
                BatteryManager.BATTERY_STATUS_FULL -> BatteryCoalescer.STATUS_FULL
                // "Not charging" means plugged in but held (e.g. by a charge
                // limiter) — still on mains, but from the player's point of
                // view it reads closest to discharging.
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> BatteryCoalescer.STATUS_DISCHARGING
                else -> BatteryCoalescer.STATUS_UNKNOWN
            }

        Log.d(TAG, "battery level=$level status=$status")
        return BatteryCoalescer.BatterySample(level, status)
    }

    private companion object {
        const val TAG = "PhoneBatterySource"
        const val VIRTUAL_CONTROLLER = 0
    }
}
