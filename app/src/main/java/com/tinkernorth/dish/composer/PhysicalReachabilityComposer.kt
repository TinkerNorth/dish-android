// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
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
        private val moonlight: MoonlightConnectionManager,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, TelemetrySink>>(scope, emptyMap()) {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun upstream(): Flow<Map<String, TelemetrySink>> =
            combine(satellite.connections, moonlight.connections, ::Pair).flatMapLatest { (satConns, moonConns) ->
                // Outer maps only re-emit on add/remove; fold each slot/pad table so
                // post-CONNECT registrations (every auto-reconnect) are picked up.
                val innerFlows: List<Flow<Any>> =
                    satConns.values.map { it.slots } + moonConns.values.map { it.pads }
                val innerTrigger: Flow<Unit> =
                    if (innerFlows.isEmpty()) flowOf(Unit) else combine(innerFlows) { }
                combine(
                    registry.devices,
                    hub.bindings,
                    hub.connections,
                    innerTrigger,
                ) { devs, binds, summ, _ ->
                    resolve(devs.keys, binds, summ, satConns, moonConns)
                }
            }

        companion object {
            fun resolve(
                deviceIds: Set<Int>,
                bindings: Map<String, String>,
                summaries: List<ConnectionSummary>,
                connections: Map<String, SatelliteConnection>,
                moonlightConnections: Map<String, MoonlightConnection> = emptyMap(),
            ): Map<String, TelemetrySink> {
                val summariesById = summaries.associateBy { it.id }
                return deviceIds
                    .map { it.toString() }
                    .mapNotNull { slotId ->
                        sinkFor(slotId, bindings, summariesById, connections, moonlightConnections)
                            ?.let { slotId to it }
                    }.toMap()
            }

            fun sinkFor(
                slotId: String,
                bindings: Map<String, String>,
                summariesById: Map<String, ConnectionSummary>,
                connections: Map<String, SatelliteConnection>,
                moonlightConnections: Map<String, MoonlightConnection> = emptyMap(),
            ): TelemetrySink? {
                val cid = bindings[slotId] ?: return null
                val summary = summariesById[cid] ?: return null
                if (summary.live != LinkState.Connected) return null
                return when (summary.kind) {
                    ConnectionKind.SATELLITE ->
                        connections[cid]?.takeIf { it.slots.value[slotId]?.registered == true }
                    // A Moonlight sink is reachable once the pad is announced on the
                    // live control stream; the connection itself gates motion on the
                    // host's MOTION_EVENT request.
                    ConnectionKind.MOONLIGHT ->
                        moonlightConnections[cid]?.takeIf { it.padFor(slotId) != null }
                    // The Bluetooth HID descriptor is a plain gamepad: no telemetry lane.
                    ConnectionKind.BLUETOOTH -> null
                }
            }

            fun connectionFor(
                slotId: String,
                bindings: Map<String, String>,
                summariesById: Map<String, ConnectionSummary>,
                connections: Map<String, SatelliteConnection>,
            ): SatelliteConnection? = sinkFor(slotId, bindings, summariesById, connections) as? SatelliteConnection
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
        moonlightConnections: Flow<Map<String, MoonlightConnection>> = flowOf(emptyMap()),
    ): Flow<Map<String, TelemetrySink>> =
        combine(connections, moonlightConnections, ::Pair).flatMapLatest { (satConns, moonConns) ->
            val innerFlows: List<Flow<Any>> =
                satConns.values.map { it.slots } + moonConns.values.map { it.pads }
            val innerTrigger: Flow<Unit> =
                if (innerFlows.isEmpty()) flowOf(Unit) else combine(innerFlows) { }
            combine(devices, bindings, summaries, innerTrigger) { devs, binds, summ, _ ->
                PhysicalReachabilityComposer.resolve(devs.keys, binds, summ, satConns, moonConns)
            }
        }

    fun resolve(
        deviceIds: Set<Int>,
        bindings: Map<String, String>,
        summaries: List<ConnectionSummary>,
        connections: Map<String, SatelliteConnection>,
    ): Map<String, TelemetrySink> = PhysicalReachabilityComposer.resolve(deviceIds, bindings, summaries, connections)

    fun connectionFor(
        slotId: String,
        bindings: Map<String, String>,
        summariesById: Map<String, ConnectionSummary>,
        connections: Map<String, SatelliteConnection>,
    ): SatelliteConnection? = PhysicalReachabilityComposer.connectionFor(slotId, bindings, summariesById, connections)
}
