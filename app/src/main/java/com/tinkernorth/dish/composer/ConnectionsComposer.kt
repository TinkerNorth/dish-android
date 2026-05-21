// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed 7-arity combine that avoids the `Array<*>` cast jungle the bare
 * `combine(vararg)` overload forces. The single unchecked cast lives here so
 * call sites stay refactor-safe — changing an upstream flow's value type
 * reshapes the [transform] lambda at compile time.
 */
@Suppress("UNCHECKED_CAST", "LongParameterList")
private inline fun <T1, T2, T3, T4, T5, T6, T7, R> combine7(
    f1: Flow<T1>,
    f2: Flow<T2>,
    f3: Flow<T3>,
    f4: Flow<T4>,
    f5: Flow<T5>,
    f6: Flow<T6>,
    f7: Flow<T7>,
    crossinline transform: suspend (T1, T2, T3, T4, T5, T6, T7) -> R,
): Flow<R> =
    combine(f1, f2, f3, f4, f5, f6, f7) { args ->
        transform(
            args[0] as T1,
            args[1] as T2,
            args[2] as T3,
            args[3] as T4,
            args[4] as T5,
            args[5] as T6,
            args[6] as T7,
        )
    }

/**
 * Derives the unified `List<ConnectionSummary>` that the UI renders. Pulled out of
 * the former `ConnectionHub` god object so the combine + `buildSummaries` logic
 * is testable in isolation.
 *
 * **Pattern:** [AbstractComposer]`<List<ConnectionSummary>>` — pure derivation
 * from seven upstream flows:
 *
 *   1. satellite sessions map (re-flattened so session-state transitions surface)
 *   2. Bluetooth slot states
 *   3. discovered servers (separates `Found` / `Ready` / `Saved`)
 *   4. slot bindings (drives `boundSlotIds`)
 *   5. controller-type swaps (drives the Xbox/PS chip on each satellite slot)
 *   6. stale satellite ids (auto-reconnect pair rejections)
 *   7. stale Bluetooth ids (KEY_MISSING / BOND_REMOVED)
 *
 * The Hub mutates the binding + type stores; this composer just reads them and
 * combines with the manager states. Hub side-effects (`detachSlot`,
 * `attachSlot`) are out of scope — composers never side-effect.
 */
@Singleton
class ConnectionsComposer
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val store: ConnectionStore,
        private val bindingStore: SlotBindingStore,
        private val typeStore: ControllerTypeStore,
        scope: CoroutineScope,
    ) : AbstractComposer<List<ConnectionSummary>>(scope, emptyList()) {
        @OptIn(ExperimentalCoroutinesApi::class)
        private val flatSatConnections: Flow<Map<String, SatelliteConnection>> =
            satellite.connections.flatMapLatest { satMap ->
                // The outer Map<String, SatelliteConnection> only re-emits when a
                // session is added/removed — *not* when an existing session's
                // state flips IDLE → CONNECTING → CONNECTED. Without flattening
                // into each session's `state` flow, the connections-list UI would
                // show stale "Connecting…" until the screen is reopened.
                if (satMap.isEmpty()) {
                    flowOf(satMap)
                } else {
                    combine(satMap.values.map { it.state }) { satMap }
                }
            }

        override fun upstream(): Flow<List<ConnectionSummary>> =
            combine7(
                flatSatConnections,
                bt.states,
                satellite.discoveredServers,
                bindingStore.state,
                typeStore.state,
                satellite.staleSatelliteIds,
                bt.staleBtIds,
            ) { satMap, btStates, discovered, bindings, satTypes, staleSat, staleBt ->
                buildSummaries(
                    satMap = satMap,
                    btStates = btStates,
                    discoveredIds = discoveredIdSet(discovered),
                    bindings = bindings,
                    satTypes = satTypes,
                    staleSatIds = staleSat,
                    staleBtIds = staleBt.keys,
                )
            }

        private fun discoveredIdSet(discovered: List<DiscoveredServer>): Set<String> =
            discovered.mapTo(mutableSetOf()) { SatelliteConnection.idFor(it) }

        private fun buildSummaries(
            satMap: Map<String, SatelliteConnection>,
            btStates: Map<String, BluetoothGamepadRegistry.SlotState>,
            discoveredIds: Set<String>,
            bindings: Map<String, String>,
            satTypes: Map<Pair<String, String>, Int>,
            staleSatIds: Set<String> = emptySet(),
            staleBtIds: Set<String> = emptySet(),
        ): List<ConnectionSummary> {
            val result = mutableListOf<ConnectionSummary>()

            val remembered = store.remembered().associateBy { it.id }
            val satIds = (remembered.keys + satMap.keys).toSet()
            for (id in satIds) {
                buildSatelliteSummary(
                    id,
                    satMap[id],
                    remembered[id],
                    bindings,
                    satTypes,
                    discoveredIds,
                    isStale = id in staleSatIds,
                )?.let(result::add)
            }

            val rememberedBtIds = mutableSetOf<String>()
            for (entry in store.rememberedBt()) {
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
            val live =
                when (conn?.state?.value) {
                    SatelliteSessionState.Live -> LinkState.Connected
                    SatelliteSessionState.Linking -> LinkState.Connecting
                    SatelliteSessionState.Faltering -> LinkState.Unstable
                    SatelliteSessionState.Idle, null ->
                        when {
                            isStale -> LinkState.Stale
                            id in discoveredIds -> LinkState.Ready
                            else -> LinkState.Saved
                        }
                }
            val bound = bindings.entries.filter { it.value == id }.map { it.key }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.SATELLITE,
                label = server.name.ifEmpty { server.ip },
                detail = "${server.ip} • UDP ${server.udpPort}",
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
                detail = "${entry.profileName} • ${entry.mac}",
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
                    slotState.registered -> "Ready to pair — find this device on your host"
                    slotState.acquiring || slotState.autoReconnecting -> "Acquiring HID profile…"
                    else -> "Idle"
                }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.BLUETOOTH,
                label = slotState.profileName ?: "Bluetooth gamepad",
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
    }
