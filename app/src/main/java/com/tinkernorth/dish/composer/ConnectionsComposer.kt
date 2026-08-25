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

@Suppress("UNCHECKED_CAST", "LongParameterList")
private inline fun <T1, T2, T3, T4, T5, T6, T7, T8, R> combine8(
    f1: Flow<T1>,
    f2: Flow<T2>,
    f3: Flow<T3>,
    f4: Flow<T4>,
    f5: Flow<T5>,
    f6: Flow<T6>,
    f7: Flow<T7>,
    f8: Flow<T8>,
    crossinline transform: suspend (T1, T2, T3, T4, T5, T6, T7, T8) -> R,
): Flow<R> =
    combine(f1, f2, f3, f4, f5, f6, f7, f8) { args ->
        transform(
            args[0] as T1,
            args[1] as T2,
            args[2] as T3,
            args[3] as T4,
            args[4] as T5,
            args[5] as T6,
            args[6] as T7,
            args[7] as T8,
        )
    }

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

private data class KnownSatellites(
    val discovered: List<DiscoveredServer>,
    val remembered: List<RememberedSatellite>,
    val rememberedBt: List<RememberedBt>,
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

        // The persisted "known" universe, folded into the combine so a remember/forget re-derives the
        // list instead of an out-of-band store read that could leave a ghost or a missing row.
        private val knownSatellites: Flow<KnownSatellites> =
            combine(
                satellite.discoveredServers,
                store.rememberedSatellitesFlow,
                store.rememberedBtFlow,
            ) { discovered, remembered, rememberedBt ->
                KnownSatellites(discovered, remembered, rememberedBt)
            }

        override fun upstream(): Flow<List<ConnectionSummary>> =
            combine8(
                flatSatConnections,
                bt.states,
                knownSatellites,
                bindingStore.state,
                typeStore.state,
                satellite.staleSatelliteIds,
                bt.staleBtIds,
                moonlightWorld,
            ) { satMap, btStates, known, bindings, satTypes, staleSat, staleBt, moonlight ->
                buildSummaries(
                    satMap = satMap,
                    btStates = btStates,
                    discoveredIds = discoveredIdSet(known.discovered),
                    remembered = known.remembered,
                    rememberedBt = known.rememberedBt,
                    bindings = bindings,
                    satTypes = satTypes,
                    staleSatIds = staleSat,
                    staleBtIds = staleBt.keys,
                    moonlight = moonlight,
                    moonlightTypes = satTypes,
                )
            }.distinctUntilChanged()

        private fun discoveredIdSet(discovered: List<DiscoveredServer>): Set<String> =
            discovered.mapTo(mutableSetOf()) { SatelliteConnection.idFor(it) }

        @Suppress("LongParameterList")
        private fun buildSummaries(
            satMap: Map<String, SatelliteConnection>,
            btStates: Map<String, BluetoothGamepadRegistry.SlotState>,
            discoveredIds: Set<String>,
            remembered: List<RememberedSatellite>,
            rememberedBt: List<RememberedBt>,
            bindings: Map<String, String>,
            satTypes: Map<Pair<String, String>, Int>,
            staleSatIds: Set<String> = emptySet(),
            staleBtIds: Set<String> = emptySet(),
            moonlight: MoonlightWorld = MoonlightWorld(emptyMap(), emptyList(), emptyList()),
            moonlightTypes: Map<Pair<String, String>, Int> = emptyMap(),
        ): List<ConnectionSummary> {
            val result = mutableListOf<ConnectionSummary>()

            val rememberedById = remembered.associateBy { it.id }
            val satIds = (rememberedById.keys + satMap.keys).toSet()
            for (id in satIds) {
                buildSatelliteSummary(
                    id,
                    satMap[id],
                    rememberedById[id],
                    bindings,
                    satTypes,
                    discoveredIds,
                    isStale = id in staleSatIds,
                )?.let(result::add)
            }

            result += buildMoonlightSummaries(moonlight, bindings, moonlightTypes)

            val rememberedBtIds = mutableSetOf<String>()
            for (entry in rememberedBt) {
                rememberedBtIds += entry.id
                result +=
                    buildRememberedBtSummary(
                        entry,
                        btStates[entry.id],
                        bindings,
                        isStale = entry.id in staleBtIds,
                    )
            }

            for ((id, slotState) in btStates) {
                if (id in rememberedBtIds) continue
                result += buildTransientBtSummary(id, slotState, bindings)
            }
            return result
        }

        private fun buildSatelliteSummary(
            id: String,
            conn: SatelliteConnection?,
            remembered: RememberedSatellite?,
            bindings: Map<String, String>,
            satTypes: Map<Pair<String, String>, Int>,
            discoveredIds: Set<String>,
            isStale: Boolean,
        ): ConnectionSummary? {
            val server = conn?.server?.value ?: remembered?.toDiscovered() ?: return null
            val live = satelliteLinkState(conn?.state?.value, isStale = isStale, isDiscovered = id in discoveredIds)
            val bound = bindings.entries.filter { it.value == id }.map { it.key }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.SATELLITE,
                label = server.name.ifEmpty { server.ip },
                detail = context.getString(R.string.discovered_row_detail, server.ip, server.udpPort),
                live = live,
                boundSlotIds = bound,
                satelliteControllerTypes = buildSlotTypes(id, bound, satTypes),
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
internal fun moonlightLinkState(
    state: MoonlightSessionState?,
    discovered: Boolean,
): LinkState =
    when (state) {
        MoonlightSessionState.Live -> LinkState.Connected
        MoonlightSessionState.Launching -> LinkState.Connecting
        MoonlightSessionState.Idle, null -> if (discovered) LinkState.Ready else LinkState.Saved
    }
