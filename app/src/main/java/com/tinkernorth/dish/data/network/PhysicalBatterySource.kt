// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.data.network.BatteryCoalescer.BatterySample
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
 *  - Otherwise — API < 31, `!isPresent`, or a USB-wired pad (which reports
 *    `isPresent == false` because it has no battery) — fall back to the
 *    *phone* battery via [PhoneBatterySource.readBattery]. The phone is the
 *    host the pad is plugged into, so its battery is the meaningful one.
 *
 * Wiring mirrors [PhysicalSlotBindingObserver]: this is a process-scoped
 * `@Singleton` observing the same cross-product of [PhysicalGamepadRegistry]
 * devices, [ConnectionHub] bindings + connection liveness, and each
 * [SatelliteConnection]'s slot table — so battery reporting survives the
 * MainActivity → GamepadOverlayActivity hand-off and starts the instant a pad
 * becomes reachable (send-on-connect). A single 30 s poll loop then iterates
 * every reachable physical pad, matching the overlay path's cadence; every
 * tick is forwarded — MSG_BATTERY is a fixed 30 s heartbeat.
 *
 * Battery is a Bluetooth-HID non-feature: the BT HID Device profile carries
 * no `MSG_BATTERY` channel, so — exactly like [SatelliteConnection.sendMotion]
 * — only SATELLITE-bound pads are polled here.
 */
@Singleton
class PhysicalBatterySource
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val satellite: SatelliteConnectionManager,
        private val statusStore: BatteryStatusStore,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        /** Phone-battery fallback for USB-wired / batteryless pads. */
        private val phoneBattery = PhoneBatterySource(context)

        /** Per-sample validation gate, same role as in [PhoneBatterySource]. */
        private val coalescer = BatteryCoalescer()

        private var bindingsJob: Job? = null
        private var pollJob: Job? = null

        /** Snapshot of `slotId -> connection` for pads currently reachable. */
        @Volatile private var reachable: Map<String, SatelliteConnection> = emptyMap()

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
                        delay(BatteryCoalescer.REPORT_INTERVAL_SECONDS * 1000L)
                    }
                }
        }

        override fun onStop(owner: LifecycleOwner) {
            bindingsJob?.cancel()
            bindingsJob = null
            pollJob?.cancel()
            pollJob = null
            reachable.keys.forEach(statusStore::clear)
            reachable = emptyMap()
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
            scope.launch { pollOnce() }
        }

        /** Read + forward the battery for every currently-reachable pad. */
        private fun pollOnce() {
            for ((slotId, conn) in reachable) {
                val deviceId = slotId.toIntOrNull() ?: continue
                val sample = sampleForDevice(deviceId)
                coalescer.publish(sample) { s ->
                    statusStore.put(slotId, s)
                    conn.sendBattery(slotId, s.level, s.status)
                }
            }
        }

        /**
         * The battery to report for the pad with [deviceId]: its own battery
         * if it is a wireless pad on API 31+, otherwise the phone (host)
         * battery. Never null — an Android host always has a battery, so the
         * unknown-level sentinel is only ever sent for a wireless pad that
         * exposes a status but no percentage.
         */
        private fun sampleForDevice(deviceId: Int): BatterySample {
            controllerSample(deviceId)?.let { return it }
            // USB-wired / batteryless pad, or API < 31 — the phone is the host.
            return phoneBattery.readBattery()
                ?: BatterySample(BatteryCoalescer.LEVEL_UNKNOWN, BatteryCoalescer.STATUS_UNKNOWN)
        }

        /**
         * A wireless pad's own battery via [InputDevice.getBatteryState], or
         * null when none is readable (API < 31, device gone, or `!isPresent`).
         */
        private fun controllerSample(deviceId: Int): BatterySample? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val device = InputDevice.getDevice(deviceId) ?: return null
            // getBatteryState() is non-null on API 31+; isPresent is the real
            // "this pad has no battery" signal that PhysicalBatteryMapping reads.
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

        private companion object {
            const val TAG = "PhysicalBatterySource"
        }
    }
