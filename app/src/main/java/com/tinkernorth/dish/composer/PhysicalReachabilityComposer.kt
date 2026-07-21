// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhysicalReachabilityComposer
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val satellite: SatelliteConnectionManager,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, SatelliteConnection>>(scope, emptyMap()) {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun upstream(): Flow<Map<String, SatelliteConnection>> =
            satellite.connections.flatMapLatest { conns ->
                // Outer map only re-emits on add/remove; fold each slot table so post-CONNECT
                // registrations (every auto-reconnect) are picked up.
                val slotFlows = conns.values.map { it.slots }
                val slotsTrigger: Flow<Unit> =
                    if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { }
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

@Deprecated(
    "Inject PhysicalReachabilityComposer instead. Its state flow is the same shape.",
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
                if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { }
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
