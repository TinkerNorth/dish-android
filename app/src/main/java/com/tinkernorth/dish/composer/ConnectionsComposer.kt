// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

// Maps the satellite session FSM to the UI LinkState. Pulled out of the composer so it is unit-testable
// without standing up the whole graph, and so the dashboard and connections screen agree by construction.
internal fun satelliteLinkState(
    state: SatelliteSessionState?,
    isStale: Boolean,
    isDiscovered: Boolean,
): LinkState =
    when (state) {
        SatelliteSessionState.Live -> LinkState.Connected
        SatelliteSessionState.Linking -> LinkState.Connecting
        SatelliteSessionState.Faltering -> LinkState.Unstable
        SatelliteSessionState.Idle, null ->
            when {
                isStale -> LinkState.Stale
                isDiscovered -> LinkState.Ready
                else -> LinkState.Saved
            }
    }

// The satellite side folded into one value: live connections, what discovery currently sees,
// what the store remembers, and which remembered ids have gone stale.
private data class SatelliteWorld(
    val connections: Map<String, SatelliteConnection>,
    val discoveredIds: Set<String>,
    val remembered: List<RememberedSatellite>,
    val staleIds: Set<String>,
)

// The Bluetooth side: live slot states, remembered hosts, and the stale ones among them.
private data class BluetoothWorld(
    val states: Map<String, BluetoothGamepadRegistry.SlotState>,
    val remembered: List<RememberedBt>,
    val staleIds: Set<String>,
)

// The slot tables every kind of host is summarised against.
private data class SlotWorld(
    val bindings: Map<String, String>,
    val types: Map<Pair<String, String>, Int>,
)

@Singleton
class ConnectionsComposer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val moonlight: MoonlightConnectionManager,
        private val store: ConnectionStore,
        private val bindingStore: SlotBindingStore,
        private val typeStore: ControllerTypeStore,
        scope: CoroutineScope,
    ) : AbstractComposer<List<ConnectionSummary>>(scope, emptyList()) {
        @OptIn(ExperimentalCoroutinesApi::class)
        private val flatSatConnections: Flow<Map<String, SatelliteConnection>> =
            satellite.connections.flatMapLatest { satMap ->
                // Outer map only re-emits on add/remove; flatten so state transitions surface.
                if (satMap.isEmpty()) {
                    flowOf(satMap)
                } else {
                    combine(satMap.values.map { it.state }) { satMap }
                }
            }

        // The Moonlight world folded into one flow (live sessions + discovered + remembered) so it can
        // ride the combine as a single source, mirroring how the satellite side is assembled.
        @OptIn(ExperimentalCoroutinesApi::class)
        private val moonlightWorld: Flow<MoonlightWorld> =
            moonlight.connections.flatMapLatest { connMap ->
                val stateTrigger: Flow<Unit> =
                    if (connMap.isEmpty()) flowOf(Unit) else combine(connMap.values.map { it.state }) { }
                combine(
                    stateTrigger,
                    moonlight.discovered,
                    moonlight.remembered,
                ) { _, discovered, remembered -> MoonlightWorld(connMap, discovered, remembered) }
            }

        // The persisted "known" universe rides each world's combine so a remember/forget re-derives
        // the list instead of an out-of-band store read that could leave a ghost or a missing row.
        private val satelliteWorld: Flow<SatelliteWorld> =
            combine(
                flatSatConnections,
                satellite.discoveredServers,
                store.rememberedSatellitesFlow,
                satellite.staleSatelliteIds,
            ) { connections, discovered, remembered, stale ->
                SatelliteWorld(connections, discoveredIdSet(discovered), remembered, stale)
            }

        private val bluetoothWorld: Flow<BluetoothWorld> =
            combine(bt.states, store.rememberedBtFlow, bt.staleBtIds) { states, remembered, stale ->
                BluetoothWorld(states, remembered, stale.keys)
            }

        private val slotWorld: Flow<SlotWorld> = combine(bindingStore.state, typeStore.state, ::SlotWorld)

        override fun upstream(): Flow<List<ConnectionSummary>> =
            combine(satelliteWorld, bluetoothWorld, slotWorld, moonlightWorld, ::buildSummaries)
                .distinctUntilChanged()

        private fun discoveredIdSet(discovered: List<DiscoveredServer>): Set<String> =
            discovered.mapTo(mutableSetOf()) { SatelliteConnection.idFor(it) }

        private fun buildSummaries(
            satellites: SatelliteWorld,
            bluetooth: BluetoothWorld,
            slots: SlotWorld,
            moonlight: MoonlightWorld,
        ): List<ConnectionSummary> {
            val result = mutableListOf<ConnectionSummary>()

            val rememberedById = satellites.remembered.associateBy { it.id }
            val satIds = (rememberedById.keys + satellites.connections.keys).toSet()
            for (id in satIds) {
                buildSatelliteSummary(
                    id,
                    satellites.connections[id],
                    rememberedById[id],
                    slots,
                    satellites.discoveredIds,
                    isStale = id in satellites.staleIds,
                )?.let(result::add)
            }

            result += buildMoonlightSummaries(moonlight, slots.bindings, slots.types)

            val rememberedBtIds = mutableSetOf<String>()
            for (entry in bluetooth.remembered) {
                rememberedBtIds += entry.id
                result +=
                    buildRememberedBtSummary(
                        entry,
                        bluetooth.states[entry.id],
                        slots.bindings,
                        isStale = entry.id in bluetooth.staleIds,
                    )
            }

            for ((id, slotState) in bluetooth.states) {
                if (id in rememberedBtIds) continue
                result += buildTransientBtSummary(id, slotState, slots.bindings)
            }
            return result
        }

        private fun buildSatelliteSummary(
            id: String,
            conn: SatelliteConnection?,
            remembered: RememberedSatellite?,
            slots: SlotWorld,
            discoveredIds: Set<String>,
            isStale: Boolean,
        ): ConnectionSummary? {
            val server = conn?.server?.value ?: remembered?.toDiscovered() ?: return null
            val live = satelliteLinkState(conn?.state?.value, isStale = isStale, isDiscovered = id in discoveredIds)
            val bound =
                slots.bindings.entries
                    .filter { it.value == id }
                    .map { it.key }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.SATELLITE,
                label = server.name.ifEmpty { server.ip },
                detail = context.getString(R.string.discovered_row_detail, server.ip, server.udpPort),
                live = live,
                boundSlotIds = bound,
                satelliteControllerTypes = buildSlotTypes(id, bound, slots.types),
            )
        }

        private fun buildRememberedBtSummary(
            entry: RememberedBt,
            slotState: BluetoothGamepadRegistry.SlotState?,
            bindings: Map<String, String>,
            isStale: Boolean,
        ): ConnectionSummary {
            val bound = bindings.entries.filter { it.value == entry.id }.map { it.key }
            val live =
                if (isStale && slotState?.connected != true) {
                    LinkState.Stale
                } else {
                    liveStateOf(slotState)
                }
            return ConnectionSummary(
                id = entry.id,
                kind = ConnectionKind.BLUETOOTH,
                label = entry.name.ifEmpty { entry.mac },
                detail = context.getString(R.string.bt_row_detail, entry.profileName, entry.mac),
                live = live,
                boundSlotIds = bound,
                btProfile = entry.profileName,
            )
        }

        private fun buildTransientBtSummary(
            id: String,
            slotState: BluetoothGamepadRegistry.SlotState,
            bindings: Map<String, String>,
        ): ConnectionSummary {
            val detail =
                when {
                    slotState.connected -> slotState.connectedName.orEmpty()
                    slotState.registered -> context.getString(R.string.bt_transient_ready_to_pair)
                    slotState.acquiring || slotState.autoReconnecting -> context.getString(R.string.bt_transient_acquiring)
                    else -> context.getString(R.string.bt_transient_idle)
                }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.BLUETOOTH,
                label = slotState.profileName ?: context.getString(R.string.default_bluetooth_gamepad_label),
                detail = detail,
                live = liveStateOf(slotState),
                boundSlotIds = bindings.entries.filter { it.value == id }.map { it.key },
                btProfile = slotState.profileName,
            )
        }

        private fun liveStateOf(slotState: BluetoothGamepadRegistry.SlotState?): LinkState =
            when {
                slotState?.connected == true -> LinkState.Connected
                slotState?.registered == true ||
                    slotState?.autoReconnecting == true ||
                    slotState?.acquiring == true -> LinkState.Connecting
                else -> LinkState.Saved
            }

        private fun buildSlotTypes(
            connId: String,
            boundSlotIds: List<String>,
            satTypes: Map<Pair<String, String>, Int>,
        ): Map<String, Int> {
            val out = mutableMapOf<String, Int>()
            for (slotId in boundSlotIds) {
                val type = satTypes[connId to slotId] ?: continue
                out[slotId] = type
            }
            return out
        }

        // Remembered hosts first, then discovered hosts not already remembered under their id.
        private fun buildMoonlightSummaries(
            world: MoonlightWorld,
            bindings: Map<String, String>,
            types: Map<Pair<String, String>, Int>,
        ): List<ConnectionSummary> {
            val rememberedById = world.remembered.associateBy { it.id }
            val discoveredById = world.discovered.associateBy { it.id }
            val ids = (rememberedById.keys + discoveredById.keys + world.connections.keys).toSet()
            return ids.map { id ->
                val conn = world.connections[id]
                val host = conn?.host?.value ?: rememberedById[id]?.toHost() ?: discoveredById.getValue(id)
                val live = moonlightLinkState(conn?.state?.value, discovered = id in discoveredById)
                val bound = bindings.entries.filter { it.value == id }.map { it.key }
                ConnectionSummary(
                    id = id,
                    kind = ConnectionKind.MOONLIGHT,
                    label = host.name.ifEmpty { host.address },
                    detail = context.getString(R.string.moonlight_row_detail, host.address),
                    live = live,
                    boundSlotIds = bound,
                    satelliteControllerTypes = buildSlotTypes(id, bound, types),
                )
            }
        }
    }

// Flattened Moonlight state: live sessions, discovered hosts, remembered hosts.
internal data class MoonlightWorld(
    val connections: Map<String, MoonlightConnection>,
    val discovered: List<MoonlightHost>,
    val remembered: List<RememberedMoonlight>,
)

// Maps the Moonlight session FSM to the shared UI LinkState (pulled out for testability).
// A dropped or host-ended session is not a live link and never a degraded one: nothing is
// routing, so it reads the same as no session at all and the binding screen says which it was.
internal fun moonlightLinkState(
    state: MoonlightSessionState?,
    discovered: Boolean,
): LinkState =
    when (state) {
        MoonlightSessionState.Live -> LinkState.Connected
        MoonlightSessionState.Launching -> LinkState.Connecting
        MoonlightSessionState.Idle,
        MoonlightSessionState.Dropped,
        MoonlightSessionState.Ended,
        null,
        -> if (discovered) LinkState.Ready else LinkState.Saved
    }
