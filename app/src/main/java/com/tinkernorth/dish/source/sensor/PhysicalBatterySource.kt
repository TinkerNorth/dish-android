// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.PhysicalReachability
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.BatteryStatusStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
        private val hub: ConnectionCoordinator,
        private val satellite: SatelliteConnectionManager,
        private val statusStore: BatteryStatusStore,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val phoneBattery = PhoneBatterySource(context)

        private val bluetoothBattery = BluetoothBatteryReader(context)

        private val validator = BatteryValidator()

        private var bindingsJob: Job? = null
        private var pollJob: Job? = null

        private var chargingReceiver: BroadcastReceiver? = null

        @Volatile private var reachable: Map<String, SatelliteConnection> = emptyMap()

        @Volatile private var lastChargingStatus: Int? = null

        override fun onStart(owner: LifecycleOwner) {
            if (bindingsJob != null) return
            bindingsJob =
                PhysicalReachability
                    .reachableSlots(
                        registry.devices,
                        hub.bindings,
                        hub.connections,
                        satellite.connections,
                    ).onEach(::onReachableChanged)
                    .launchIn(scope)
            pollJob =
                scope.launch {
                    while (isActive) {
                        pollOnce()
                        delay(BatteryValidator.REPORT_INTERVAL_SECONDS * 1000L)
                    }
                }
            registerChargingReceiver()
        }

        override fun onStop(owner: LifecycleOwner) {
            bindingsJob?.cancel()
            bindingsJob = null
            pollJob?.cancel()
            pollJob = null
            chargingReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            chargingReceiver = null
            lastChargingStatus = null
            reachable.keys.forEach(statusStore::clear)
            reachable = emptyMap()
        }

        private fun registerChargingReceiver() {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context?,
                        intent: Intent?,
                    ) {
                        val status = intent?.let(::chargingStatusOf) ?: return
                        if (status == lastChargingStatus) return
                        lastChargingStatus = status
                        Log.d(TAG, "host charging state changed -> $status, polling pads")
                        scope.launch { pollOnce() }
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            chargingReceiver = receiver
        }

        private fun onReachableChanged(next: Map<String, SatelliteConnection>) {
            (reachable.keys - next.keys).forEach(statusStore::clear)
            reachable = next
            // Hop off the flow-collector thread; pollOnce is synchronous.
            scope.launch { pollOnce() }
        }

        private fun pollOnce() {
            for ((slotId, conn) in reachable) {
                val deviceId = slotId.toIntOrNull() ?: continue
                val sample = sampleForDevice(deviceId)
                validator.publish(sample) { s ->
                    statusStore.put(slotId, s)
                    conn.sendBattery(slotId, s.level, s.status)
                }
            }
        }

        private fun sampleForDevice(deviceId: Int): BatterySample {
            controllerSample(deviceId)?.let { return it }
            return phoneBattery.readBattery()
                ?: BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_UNKNOWN)
        }

        private fun controllerSample(deviceId: Int): BatterySample? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = InputDevice.getDevice(deviceId) ?: return null
                val state = device.batteryState
                val sample =
                    PhysicalBatteryMapping.controllerSample(
                        isPresent = state.isPresent,
                        capacity = state.capacity,
                        status = state.status,
                    )
                if (sample != null) Log.d(TAG, "pad $deviceId own battery $sample")
                return sample
            }
            // API 24–30: no getBatteryState(), use BT reflection fallback.
            val name = InputDevice.getDevice(deviceId)?.name ?: return null
            val level = bluetoothBattery.readLevel(name) ?: return null
            Log.d(TAG, "pad $deviceId BT battery level=$level (API<31 reflection)")
            return BatterySample(level, BatteryValidator.STATUS_UNKNOWN)
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
