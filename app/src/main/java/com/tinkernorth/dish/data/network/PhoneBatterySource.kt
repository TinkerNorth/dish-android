// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.tinkernorth.dish.data.network.BatteryValidator.BatterySample
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reports the phone's own battery as the "controller battery". Closes the
 * Android half of roadmap Task 1.2.
 *
 * Two callers share this:
 *  - the touch overlay ([com.tinkernorth.dish.ui.main.GamepadOverlayActivity])
 *    runs the 30 s poll loop ([start] / [stop]) so the *virtual* controller
 *    reports the phone battery — the phone is the controller there;
 *  - [PhysicalBatterySource] reads [readBattery] one-shot as the *fallback*
 *    for a USB-wired physical pad (the phone is the host, so its battery is
 *    the meaningful one).
 *
 * The poll loop scrapes the sticky `ACTION_BATTERY_CHANGED` intent every
 * [BatteryValidator.REPORT_INTERVAL_SECONDS] (30 s, matching the receiver's
 * `BATTERY_REPORT_INTERVAL_SEC`) plus once immediately on [start]. Every
 * tick is forwarded — MSG_BATTERY is a fixed 30 s heartbeat, so an unchanged
 * value still reaches the wire and a dropped UDP packet self-heals.
 *
 * On top of the 30 s heartbeat, [start] also registers an
 * `ACTION_BATTERY_CHANGED` [BroadcastReceiver]: the protocol requires a report
 * **whenever the charging state transitions** (§0x000B), not only on the 30 s
 * boundary. The receiver emits an extra report the instant the phone's
 * charging state flips so the host UI reacts without up to a 30 s lag. It is
 * unregistered in [stop], so it is strictly lifecycle-scoped — no leak.
 */
class PhoneBatterySource(
    private val context: Context,
    private val validator: BatteryValidator = BatteryValidator(),
    /** Slot id this source reports under — the virtual controller by default. */
    private val slotId: String = VIRTUAL_SLOT_ID,
    /** Optional UI cache; the latest sample is mirrored here for the dashboard. */
    private val statusStore: BatteryStatusStore? = null,
) {
    /** Invoked with a wire-ready (level, status) pair when the value changes. */
    fun interface Emit {
        fun emit(
            level: Int,
            status: Int,
        )
    }

    private var job: Job? = null

    /** Charging-state change receiver; non-null only while [start]ed. */
    private var chargingReceiver: BroadcastReceiver? = null

    /** Last charging status emitted, so the receiver only fires on a real flip. */
    @Volatile private var lastStatus: Int? = null

    /** Begin the 30 s poll loop on [scope]. Safe to call twice (re-arms). */
    fun start(
        scope: CoroutineScope,
        emit: Emit,
    ) {
        stop()
        job =
            scope.launch {
                while (isActive) {
                    readBattery()?.let { sample -> forward(sample, emit) }
                    delay(BatteryValidator.REPORT_INTERVAL_SECONDS * 1000L)
                }
            }
        registerChargingReceiver(emit)
    }

    /** Stop polling and release the charging-state receiver. Safe when not started. */
    fun stop() {
        job?.cancel()
        job = null
        chargingReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        chargingReceiver = null
        lastStatus = null
    }

    /**
     * Register the `ACTION_BATTERY_CHANGED` receiver. Android delivers a fresh
     * broadcast on every battery change — we filter it down to *charging-state
     * transitions* so the wire only sees the extra report the protocol asks
     * for, not a packet on every 1 % level tick.
     */
    private fun registerChargingReceiver(emit: Emit) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    val sample = intent?.let(::sampleFromIntent) ?: return
                    if (sample.status == lastStatus) return
                    Log.d(TAG, "charging state changed -> ${sample.status}")
                    forward(sample, emit)
                }
            }
        // registerReceiver returns the current sticky intent; the poll loop's
        // immediate first read already covers the initial report, so only
        // subsequent charging-state transitions are acted on here.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        chargingReceiver = receiver
    }

    /** Validate, mirror to the store, remember the status, and emit. */
    private fun forward(
        sample: BatterySample,
        emit: Emit,
    ) {
        validator.publish(sample) { s ->
            statusStore?.put(slotId, s)
            lastStatus = s.status
            emit.emit(s.level, s.status)
        }
    }

    /**
     * Read the current phone battery level + charging state, or null if
     * unavailable. Exposed (not private) so [PhysicalBatterySource] can reuse
     * it as the fallback for a physical pad with no battery of its own.
     */
    fun readBattery(): BatterySample? {
        val intent: Intent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
        return sampleFromIntent(intent)
    }

    /** Decode a wire [BatterySample] from an `ACTION_BATTERY_CHANGED` intent. */
    private fun sampleFromIntent(intent: Intent): BatterySample {
        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val level =
            if (rawLevel >= 0 && scale > 0) {
                (rawLevel * 100 / scale).coerceIn(0, 100)
            } else {
                BatteryValidator.LEVEL_UNKNOWN
            }

        val status =
            when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> BatteryValidator.STATUS_CHARGING
                BatteryManager.BATTERY_STATUS_FULL -> BatteryValidator.STATUS_FULL
                // "Not charging" means plugged in but held (e.g. by a charge
                // limiter) — still on mains, but from the player's point of
                // view it reads closest to discharging.
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> BatteryValidator.STATUS_DISCHARGING
                else -> BatteryValidator.STATUS_UNKNOWN
            }

        Log.d(TAG, "battery level=$level status=$status")
        return BatterySample(level, status)
    }

    private companion object {
        const val TAG = "PhoneBatterySource"
    }
}
