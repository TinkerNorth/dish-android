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
    private val validator: BatteryValidator = BatteryValidator(),
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
                    delay(BatteryValidator.REPORT_INTERVAL_SECONDS * 1000L)
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
        // Seed lastStatus from the sticky intent so the replay isn't mistaken for a transition.
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
        validator.publish(sample) { s ->
            statusStore?.put(slotId, s)
            lastStatus = s.status
            emit.emit(s.level, s.status)
        }
    }

    fun readBattery(): BatterySample? {
        val intent: Intent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
        return sampleFromIntent(intent)
    }

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
                // NOT_CHARGING is plugged-but-held; reported as discharging to match player perception.
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
