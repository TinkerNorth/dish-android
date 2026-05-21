// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.PhysicalSlotBindingObserver
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.sensor.PhysicalBatterySource
import com.tinkernorth.dish.source.sensor.PhysicalMotionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared reachability logic for [PhysicalBatterySource] and [PhysicalMotionSource]:
 * which physical pads can currently accept a satellite report, and — critically —
 * *when* that set must be recomputed.
 *
 * **Pattern:** [AbstractComposer]`<Map<String, SatelliteConnection>>` — pure
 * derivation from four upstream flows (devices, bindings, summaries, connections)
 * plus an implicit fifth (each live connection's slot table). Lives in
 * `composer/` because that's where derivations live; the upstreams come from
 * three different `AbstractStateSource`s.
 *
 * A pad is reachable when it is bound to a SATELLITE connection that is CONNECTED
 * and whose slot for the pad has flipped `registered` — exactly the gate
 * [SatelliteConnection.sendBattery] / [SatelliteConnection.sendMotion] enforce
 * (both drop a report for an unregistered slot).
 *
 * The subtle part is the timing. `registered` flips on the `addController` ACK,
 * 0–2 s **after** the connection reaches CONNECTED. On auto-reconnect the binding
 * already exists, so at that moment nothing in `devices` / `bindings` /
 * `connections` changes. A reachability flow built from only those three inputs
 * evaluates the pad once — while still unregistered — and never re-evaluates it,
 * so physical-pad motion and battery silently never start. This composer
 * therefore also folds in every live connection's `slots` `StateFlow` as a
 * re-emit trigger, the same fix [PhysicalSlotBindingObserver] already makes.
 */
@Singleton
class PhysicalReachabilityComposer
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val satellite: SatelliteConnectionManager,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, SatelliteConnection>>(scope, emptyMap()) {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun upstream(): Flow<Map<String, SatelliteConnection>> =
            satellite.connections.flatMapLatest { conns ->
                // The connection map only re-emits when a session is added or
                // removed; re-derive whenever an existing session's slot table
                // flips `registered` too, otherwise a pad that registers after
                // CONNECT (every auto-reconnect) is never picked up.
                val slotFlows = conns.values.map { it.slots }
                val slotsTrigger: Flow<Unit> =
                    if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { Unit }
                combine(
                    registry.devices,
                    hub.bindings,
                    hub.connections,
                    slotsTrigger,
                ) { devs, binds, summ, _ ->
                    resolve(devs.keys, binds, summ, conns)
                }
            }

        companion object {
            /**
             * Pure resolution of the reachable map. Kept as a top-level function on
             * the companion so it remains JVM-unit-testable without spinning up the
             * composer + scope. Used by both the composer and by the legacy tests
             * in `PhysicalReachabilityTest`.
             */
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
             * The [SatelliteConnection] the pad at [slotId] can currently send to,
             * or null when it isn't reachable.
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
                        summary.live == LinkState.Connected
                if (!onLiveSatellite) return null
                val conn = connections[cid] ?: return null
                return conn.takeIf { it.slots.value[slotId]?.registered == true }
            }
        }
    }

/**
 * Backwards-compat shim for callers that still reference `PhysicalReachability`
 * directly. New code should inject [PhysicalReachabilityComposer] and read its
 * `state` flow. This object will be removed once both consumers
 * ([PhysicalMotionSource], [PhysicalBatterySource]) have been migrated to the
 * composer.
 */
@Deprecated(
    "Inject PhysicalReachabilityComposer instead — its state flow is the same shape.",
    ReplaceWith("PhysicalReachabilityComposer"),
)
internal object PhysicalReachability {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun reachableSlots(
        devices: Flow<Map<Int, PhysicalGamepadRegistry.Device>>,
        bindings: Flow<Map<String, String>>,
        summaries: Flow<List<ConnectionSummary>>,
        connections: Flow<Map<String, SatelliteConnection>>,
    ): Flow<Map<String, SatelliteConnection>> =
        connections.flatMapLatest { conns ->
            val slotFlows = conns.values.map { it.slots }
            val slotsTrigger: Flow<Unit> =
                if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { Unit }
            combine(devices, bindings, summaries, slotsTrigger) { devs, binds, summ, _ ->
                PhysicalReachabilityComposer.resolve(devs.keys, binds, summ, conns)
            }
        }

    fun resolve(
        deviceIds: Set<Int>,
        bindings: Map<String, String>,
        summaries: List<ConnectionSummary>,
        connections: Map<String, SatelliteConnection>,
    ): Map<String, SatelliteConnection> = PhysicalReachabilityComposer.resolve(deviceIds, bindings, summaries, connections)

    fun connectionFor(
        slotId: String,
        bindings: Map<String, String>,
        summariesById: Map<String, ConnectionSummary>,
        connections: Map<String, SatelliteConnection>,
    ): SatelliteConnection? = PhysicalReachabilityComposer.connectionFor(slotId, bindings, summariesById, connections)
}
