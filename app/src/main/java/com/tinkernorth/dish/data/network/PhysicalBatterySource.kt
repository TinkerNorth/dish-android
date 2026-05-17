// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.data.network.BatteryValidator.BatterySample
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports a *physical* gamepad's battery to the satellite it is routed to —
 * the physical-controller half of roadmap Task 1.2, mirroring what
 * [PhoneBatterySource] does for the on-screen touch controller.
 *
 * Per bound physical pad it picks the battery to report as follows:
 *
 *  - On API 31+ ([Build.VERSION_CODES.S]) query [InputDevice.getBatteryState].
 *    If the pad is wireless it exposes its own battery (`isPresent == true`) —
 *    that capacity + status, mapped by [PhysicalBatteryMapping], is the
 *    controller's battery and is reported as-is.
 *  - On API 24–30, where `getBatteryState()` does not exist, fall back to the
 *    [BluetoothBatteryReader] — a hidden-API reflection read of the bonded
 *    Bluetooth device's `getBatteryLevel()`. This is best-effort: it yields a
 *    level only for a wireless pad whose name matches a bonded device and only
 *    when the ROM exposes the hidden method.
 *  - Otherwise — `!isPresent`, a USB-wired pad (which reports `isPresent ==
 *    false` because it has no battery), or the API < 31 reflection finding
 *    nothing — fall back to the *phone* battery via [PhoneBatterySource.readBattery].
 *    The phone is the host the pad is plugged into, so its battery is the
 *    meaningful one.
 *
 * Wiring mirrors [PhysicalSlotBindingObserver]: this is a process-scoped
 * `@Singleton` observing the same cross-product of [PhysicalGamepadRegistry]
 * devices, [ConnectionHub] bindings + connection liveness, and each
 * [SatelliteConnection]'s slot table — so battery reporting survives the
 * MainActivity → GamepadOverlayActivity hand-off and starts the instant a pad
 * becomes reachable (send-on-connect). A 30 s poll loop iterates every
 * reachable physical pad, matching the overlay path's cadence; every tick is
 * forwarded — MSG_BATTERY is a fixed 30 s heartbeat.
 *
 * The protocol also wants a report **whenever the charging state transitions**
 * (§0x000B), not only on the 30 s boundary. An `ACTION_BATTERY_CHANGED`
 * [BroadcastReceiver], registered while started, triggers an immediate poll
 * when the host's charging state flips; [pollOnce] then re-reads every
 * reachable pad and forwards the change without waiting up to 30 s. The
 * receiver is unregistered in [onStop], so it is strictly lifecycle-scoped.
 *
 * Battery is a Bluetooth-HID non-feature: the BT HID Device profile carries
 * no `MSG_BATTERY` channel, so — exactly like [SatelliteConnection.sendMotion]
 * — only SATELLITE-bound pads are polled here.
 */
@Singleton
class PhysicalBatterySource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val satellite: SatelliteConnectionManager,
        private val statusStore: BatteryStatusStore,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        /** Phone-battery fallback for USB-wired / batteryless pads. */
        private val phoneBattery = PhoneBatterySource(context)

        /** API < 31 hidden-API reader for a bonded BT pad's own battery. */
        private val bluetoothBattery = BluetoothBatteryReader(context)

        /** Per-sample validation gate, same role as in [PhoneBatterySource]. */
        private val validator = BatteryValidator()

        private var bindingsJob: Job? = null
        private var pollJob: Job? = null

        /** Charging-state change receiver; non-null only while started. */
        private var chargingReceiver: BroadcastReceiver? = null

        /** Snapshot of `slotId -> connection` for pads currently reachable. */
        @Volatile private var reachable: Map<String, SatelliteConnection> = emptyMap()

        /** Last host charging status seen, so the receiver fires only on a flip. */
        @Volatile private var lastChargingStatus: Int? = null

        override fun onStart(owner: LifecycleOwner) {
            if (bindingsJob != null) return
            bindingsJob =
                reachableSlots()
                    .onEach(::onReachableChanged)
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

        /**
         * Register the host `ACTION_BATTERY_CHANGED` receiver. A physical pad
         * exposes no Android-observable charging event of its own, so the
         * host's charging-state transition is the only signal available — and
         * it is also exactly what a phone-fallback pad needs. On a flip we
         * trigger an immediate [pollOnce], which re-reads every reachable pad
         * and forwards any change rather than waiting for the 30 s tick.
         */
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
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            chargingReceiver = receiver
        }

        /**
         * Stream of `slotId -> SatelliteConnection` for every physical pad
         * currently bound to a CONNECTED satellite whose slot is registered.
         * The same cross-product [PhysicalSlotBindingObserver] derives its
         * native bindings from — battery can only flow once the controller is
         * registered, so we gate on exactly what [SatelliteConnection.sendBattery]
         * requires.
         */
        private fun reachableSlots(): Flow<Map<String, SatelliteConnection>> =
            combine(
                registry.devices,
                hub.bindings,
                hub.connections,
            ) { devices, bindings, summaries ->
                resolveReachable(devices.keys, bindings, summaries)
            }

        /**
         * Resolve which physical slots can currently accept battery: bound to
         * a CONNECTED satellite connection whose slot for this pad is
         * registered. Bluetooth bindings are excluded (no battery channel).
         */
        private fun resolveReachable(
            deviceIds: Set<Int>,
            bindings: Map<String, String>,
            summaries: List<ConnectionSummary>,
        ): Map<String, SatelliteConnection> {
            val byId = summaries.associateBy { it.id }
            return deviceIds
                .associateBy { it.toString() }
                .mapNotNull { (slotId, _) ->
                    reachableConnection(slotId, bindings, byId)?.let { slotId to it }
                }.toMap()
        }

        /**
         * The [SatelliteConnection] the pad at [slotId] can currently report
         * battery to, or null when it isn't reachable: not bound, bound to
         * Bluetooth (no battery channel), the satellite isn't CONNECTED, or
         * the controller isn't registered yet (`sendBattery` would drop it).
         */
        private fun reachableConnection(
            slotId: String,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
        ): SatelliteConnection? {
            val cid = bindings[slotId] ?: return null
            val summary = summariesById[cid] ?: return null
            val onLiveSatellite =
                summary.kind == ConnectionKind.SATELLITE &&
                    summary.live == ConnectionLive.CONNECTED
            if (!onLiveSatellite) return null
            val conn = satellite.get(cid) ?: return null
            return conn.takeIf { it.slots.value[slotId]?.registered == true }
        }

        /**
         * React to a change in the reachable-slot set: clear the cached state
         * for pads that just dropped off, then poll the new set immediately so
         * a freshly-connected pad reports on connect rather than waiting up to
         * 30 s for the next loop tick.
         */
        private fun onReachableChanged(next: Map<String, SatelliteConnection>) {
            (reachable.keys - next.keys).forEach(statusStore::clear)
            reachable = next
            // pollOnce() is synchronous and returns immediately (no suspend),
            // so this launch is a one-shot that completes before it could be
            // observed as outstanding — not an untracked long-lived coroutine.
            // It exists only to hop off the flow-collector / receiver thread.
            scope.launch { pollOnce() }
        }

        /** Read + forward the battery for every currently-reachable pad. */
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

        /**
         * The battery to report for the pad with [deviceId]: its own battery
         * if it is a wireless pad (read via `getBatteryState()` on API 31+, or
         * the [BluetoothBatteryReader] reflection fallback below), otherwise
         * the phone (host) battery. Never null — an Android host always has a
         * battery, so the unknown-level sentinel is only ever sent for a
         * wireless pad that exposes a status but no percentage.
         */
        private fun sampleForDevice(deviceId: Int): BatterySample {
            controllerSample(deviceId)?.let { return it }
            // USB-wired / batteryless pad, or no own-battery reading — the
            // phone is the host the pad is plugged into.
            return phoneBattery.readBattery()
                ?: BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_UNKNOWN)
        }

        /**
         * A wireless pad's own battery, or null when none is readable (device
         * gone, USB-wired, or — on AOSP variants — the hidden API is absent).
         *
         *  - API 31+: [InputDevice.getBatteryState], the public path.
         *  - API 24–30: [BluetoothBatteryReader], the hidden-API reflection
         *    fallback (M-1) — without this, an API < 31 wireless pad's own
         *    battery was never read and the phone battery was mis-reported as
         *    the controller's. Level only (no charging status is reliably
         *    reflectable across ROMs), so it pairs with [BatteryValidator.STATUS_UNKNOWN].
         */
        private fun controllerSample(deviceId: Int): BatterySample? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = InputDevice.getDevice(deviceId) ?: return null
                // getBatteryState() is non-null on API 31+; isPresent is the
                // real "this pad has no battery" signal PhysicalBatteryMapping reads.
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
            // API 24–30 — no getBatteryState(); try the BT reflection fallback.
            val name = InputDevice.getDevice(deviceId)?.name ?: return null
            val level = bluetoothBattery.readLevel(name) ?: return null
            Log.d(TAG, "pad $deviceId BT battery level=$level (API<31 reflection)")
            return BatterySample(level, BatteryValidator.STATUS_UNKNOWN)
        }

        /**
         * The wire charging status of an `ACTION_BATTERY_CHANGED` intent — used
         * only to detect host charging-state transitions. Mirrors the mapping
         * in [PhoneBatterySource.sampleFromIntent].
         */
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
