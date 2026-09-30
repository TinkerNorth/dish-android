// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PhoneBatterySource(
    private val context: Context,
    private val slotId: String = VIRTUAL_SLOT_ID,
    private val statusStore: BatteryStatusStore? = null,
) {
    fun interface Emit {
        fun emit(
            level: Int,
            status: Int,
        )
    }

    private var job: Job? = null

    private var chargingReceiver: BroadcastReceiver? = null

    @Volatile private var lastStatus: Int? = null

    fun start(
        scope: CoroutineScope,
        emit: Emit,
    ) {
        stop()
        job =
            scope.launch {
                while (isActive) {
                    readBattery()?.let { sample -> forward(sample, emit) }
                    delay(BatteryValidator.REPORT_INTERVAL_MS)
                }
            }
        registerChargingReceiver(emit)
    }

    fun stop() {
        job?.cancel()
        job = null
        chargingReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        chargingReceiver = null
        lastStatus = null
    }

    // Takes the emitter through its constructor rather than capturing it, so the receiver can be
    // read on its own.
    private inner class ChargingReceiver(
        private val emit: Emit,
    ) : BroadcastReceiver() {
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

    private fun registerChargingReceiver(emit: Emit) {
        val receiver = ChargingReceiver(emit)
        val sticky =
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        sticky?.let { lastStatus = sampleFromIntent(it).status }
        chargingReceiver = receiver
    }

    private fun forward(
        sample: BatterySample,
        emit: Emit,
    ) {
        publishBatterySample(sample) { valid -> onValidSample(valid, emit) }
    }

    private fun onValidSample(
        sample: BatterySample,
        emit: Emit,
    ) {
        statusStore?.put(slotId, sample)
        lastStatus = sample.status
        emit.emit(sample.level, sample.status)
    }

    fun readBattery(): BatterySample? {
        val intent: Intent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
        return sampleFromIntent(intent)
    }

    private fun sampleFromIntent(intent: Intent): BatterySample {
        val level =
            phoneBatteryLevel(
                rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, EXTRA_ABSENT),
                scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, EXTRA_ABSENT),
            )
        val status = phoneBatteryStatus(intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN))
        Log.d(TAG, "battery level=$level status=$status")
        return BatterySample(level, status)
    }

    private companion object {
        const val TAG = "PhoneBatterySource"
        const val EXTRA_ABSENT = -1
    }
}

private const val PERCENT = 100

// The scale is the platform's own denominator and is not always 100; a broadcast that carries
// neither is a sticky one that arrived before the first real reading.
internal fun phoneBatteryLevel(
    rawLevel: Int,
    scale: Int,
): Int {
    val isReadable = rawLevel >= 0 && scale > 0
    if (!isReadable) return BatteryValidator.LEVEL_UNKNOWN
    return (rawLevel * PERCENT / scale).coerceIn(0, PERCENT)
}

// NOT_CHARGING is plugged-but-held; reported as discharging to match player perception.
internal fun phoneBatteryStatus(platformStatus: Int): Int =
    when (platformStatus) {
        BatteryManager.BATTERY_STATUS_CHARGING -> BatteryValidator.STATUS_CHARGING
        BatteryManager.BATTERY_STATUS_FULL -> BatteryValidator.STATUS_FULL
        BatteryManager.BATTERY_STATUS_DISCHARGING,
        BatteryManager.BATTERY_STATUS_NOT_CHARGING,
        -> BatteryValidator.STATUS_DISCHARGING
        else -> BatteryValidator.STATUS_UNKNOWN
    }
