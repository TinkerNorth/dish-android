// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Shared reachability logic for [PhysicalBatterySource] and
 * [PhysicalMotionSource]: which physical pads can currently accept a satellite
 * report, and — critically — *when* that set must be recomputed.
 *
 * A pad is reachable when it is bound to a SATELLITE connection that is
 * CONNECTED and whose slot for the pad has flipped `registered` — exactly the
 * gate [SatelliteConnection.sendBattery] / [SatelliteConnection.sendMotion]
 * enforce (both drop a report for an unregistered slot).
 *
 * The subtle part is the timing. `registered` flips on the `addController`
 * ACK, 0–2 s **after** the connection reaches CONNECTED (see
 * [SatelliteConnection.sendReport]). On auto-reconnect the binding already
 * exists, so at that moment nothing in `devices` / `bindings` / `connections`
 * changes. A reachability flow built from only those three inputs evaluates
 * the pad once — while still unregistered — and never re-evaluates it, so
 * physical-pad motion and battery silently never start. [reachableSlots]
 * therefore also folds in every live connection's `slots` `StateFlow` as a
 * re-emit trigger, the same fix [PhysicalSlotBindingObserver] already makes.
 */
internal object PhysicalReachability {
    /**
     * A flow of the reachable `slotId -> connection` map. Re-emits when the
     * device / binding / connection inputs change **and** when any live
     * connection's slot table flips `registered`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun reachableSlots(
        devices: Flow<Map<Int, PhysicalGamepadRegistry.Device>>,
        bindings: Flow<Map<String, String>>,
        summaries: Flow<List<ConnectionSummary>>,
        connections: Flow<Map<String, SatelliteConnection>>,
    ): Flow<Map<String, SatelliteConnection>> =
        connections.flatMapLatest { conns ->
            // The connection map only re-emits when a session is added or
            // removed; re-derive whenever an existing session's slot table
            // flips `registered` too, otherwise a pad that registers after
            // CONNECT (every auto-reconnect) is never picked up.
            val slotFlows = conns.values.map { it.slots }
            val slotsTrigger: Flow<Unit> =
                if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { Unit }
            combine(devices, bindings, summaries, slotsTrigger) { devs, binds, summ, _ ->
                resolve(devs.keys, binds, summ, conns)
            }
        }

    /** Pure resolution of the reachable map. Unit-tested without flows. */
    fun resolve(
        deviceIds: Set<Int>,
        bindings: Map<String, String>,
        summaries: List<ConnectionSummary>,
        connections: Map<String, SatelliteConnection>,
    ): Map<String, SatelliteConnection> {
        val summariesById = summaries.associateBy { it.id }
        return deviceIds
            .map { it.toString() }
            .mapNotNull { slotId ->
                connectionFor(slotId, bindings, summariesById, connections)
                    ?.let { slotId to it }
            }.toMap()
    }

    /**
     * The [SatelliteConnection] the pad at [slotId] can currently send to, or
     * null when it isn't reachable: not bound, bound to Bluetooth (no
     * motion/battery channel), the satellite isn't CONNECTED, the connection
     * object is gone, or the controller's slot has not registered yet.
     */
    fun connectionFor(
        slotId: String,
        bindings: Map<String, String>,
        summariesById: Map<String, ConnectionSummary>,
        connections: Map<String, SatelliteConnection>,
    ): SatelliteConnection? {
        val cid = bindings[slotId] ?: return null
        val summary = summariesById[cid] ?: return null
        val onLiveSatellite =
            summary.kind == ConnectionKind.SATELLITE &&
                summary.live == ConnectionLive.CONNECTED
        if (!onLiveSatellite) return null
        val conn = connections[cid] ?: return null
        return conn.takeIf { it.slots.value[slotId]?.registered == true }
    }
}
