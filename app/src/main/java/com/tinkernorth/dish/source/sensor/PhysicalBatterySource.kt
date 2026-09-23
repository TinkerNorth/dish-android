// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.PhysicalReachabilityComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.BatteryStatusStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhysicalBatterySource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val reachability: PhysicalReachabilityComposer,
        private val statusStore: BatteryStatusStore,
        private val scope: CoroutineScope,
        private val reader: PadBatteryReader,
    ) : DefaultLifecycleObserver {
        private val phoneBattery = PhoneBatterySource(context)

        private val validator = BatteryValidator()

        private val polls = Channel<Unit>(Channel.CONFLATED)

        private var reachableJob: Job? = null
        private var devicesJob: Job? = null
        private var tickJob: Job? = null
        private var pollJob: Job? = null

        private var chargingReceiver: BroadcastReceiver? = null

        @Volatile private var reachable: Map<String, TelemetrySink> = emptyMap()

        @Volatile private var deviceKeys: Set<String> = emptySet()

        @Volatile private var lastChargingStatus: Int? = null

        override fun onStart(owner: LifecycleOwner) {
            if (pollJob != null) return
            pollJob =
                scope.launch {
                    polls.receiveAsFlow().collect { pollOnce() }
                }
            reachableJob =
                reachability.state
                    .onEach(::onReachableChanged)
                    .launchIn(scope)
            devicesJob =
                registry.devices
                    .map { devs -> devs.mapValues { (_, d) -> d.transport } }
                    .distinctUntilChanged()
                    .onEach(::onDevicesChanged)
                    .launchIn(scope)
            tickJob =
                scope.launch {
                    while (isActive) {
                        requestPoll()
                        delay(BatteryValidator.REPORT_INTERVAL_SECONDS * 1000L)
                    }
                }
            registerChargingReceiver()
        }

        override fun onStop(owner: LifecycleOwner) {
            reachableJob?.cancel()
            reachableJob = null
            devicesJob?.cancel()
            devicesJob = null
            tickJob?.cancel()
            tickJob = null
            pollJob?.cancel()
            pollJob = null
            chargingReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            chargingReceiver = null
            lastChargingStatus = null
            deviceKeys.forEach(statusStore::clear)
            deviceKeys = emptySet()
            reachable = emptyMap()
        }

        private fun requestPoll() {
            polls.trySend(Unit)
        }

        private inner class HostChargingReceiver : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context?,
                intent: Intent?,
            ) {
                val status = intent?.let(::chargingStatusOf) ?: return
                if (status == lastChargingStatus) return
                lastChargingStatus = status
                Log.d(TAG, "host charging state changed -> $status, polling pads")
                requestPoll()
            }
        }

        private fun registerChargingReceiver() {
            val receiver = HostChargingReceiver()
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            chargingReceiver = receiver
        }

        private fun onReachableChanged(next: Map<String, TelemetrySink>) {
            reachable = next
            requestPoll()
        }

        private fun onDevicesChanged(next: Map<Int, Transport>) {
            val keys = next.keys.mapTo(mutableSetOf(), Int::toString)
            (deviceKeys - keys).forEach(statusStore::clear)
            deviceKeys = keys
            requestPoll()
        }

        private fun pollOnce() {
            val devices = registry.devices.value
            reader.retain(devices.keys)
            val phone = phoneBattery.readBattery()
            for ((deviceId, device) in devices) {
                if (device.transitioning || device.isDisconnecting) continue
                val slotId = deviceId.toString()
                val routed = route(device.transport, reader.sample(device), phone)
                publishDisplay(slotId, routed.display)
                val conn = reachable[slotId] ?: continue
                validator.publish(routed.wire) { s ->
                    conn.sendBattery(slotId, s.level, s.status)
                }
            }
        }

        private fun publishDisplay(
            slotId: String,
            sample: BatterySample?,
        ) {
            if (sample == null) {
                statusStore.clear(slotId)
                return
            }
            validator.publish(sample) { s -> statusStore.put(slotId, s) }
        }

        private fun chargingStatusOf(intent: Intent): Int =
            when (intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> BatteryValidator.STATUS_CHARGING
                android.os.BatteryManager.BATTERY_STATUS_FULL -> BatteryValidator.STATUS_FULL
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> BatteryValidator.STATUS_DISCHARGING
                else -> BatteryValidator.STATUS_UNKNOWN
            }

        private companion object {
            const val TAG = "PhysicalBatterySource"
        }
    }
