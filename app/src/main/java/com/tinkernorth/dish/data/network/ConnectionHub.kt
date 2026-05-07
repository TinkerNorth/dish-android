// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kind of a connection target. Bluetooth is capped at a single active host by
 * Android's HID Device profile, but the hub still tracks multiple remembered
 * hosts and exposes them as separate [ConnectionSummary] entries so the user
 * can switch between them.
 */
enum class ConnectionKind { SATELLITE, BLUETOOTH }

enum class ConnectionLive { IDLE, CONNECTING, CONNECTED }

/**
 * Snapshot of a single remembered or live connection, used by the UI to render
 * the connections list and the bind-to-connection picker on each slot.
 */
data class ConnectionSummary(
    val id: String,
    val kind: ConnectionKind,
    val label: String,
    val detail: String,
    val live: ConnectionLive,
    val boundSlotId: String?,
    /** For BT only: name of the remembered [BluetoothGamepad.GamepadProfile]. */
    val btProfile: String? = null,
)

/**
 * Single place every slot talks to. Aggregates [SatelliteConnectionManager] and
 * [BluetoothGamepadRegistry] into one stream of [ConnectionSummary] entries.
 *
 * Binding a slot to a connection is a UI-level concept (which slot routes its
 * input where) — the actual wiring lives in the managers; the hub just tracks
 * the bindings so at most one slot owns any given connection.
 */
@Singleton
class ConnectionHub
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val store: ConnectionStore,
        private val scope: CoroutineScope,
    ) {
        /** slotId -> connectionId of the connection currently routing that slot. */
        private val _bindings = MutableStateFlow<Map<String, String>>(emptyMap())
        val bindings: StateFlow<Map<String, String>> = _bindings.asStateFlow()

        private val _connections = MutableStateFlow<List<ConnectionSummary>>(emptyList())
        val connections: StateFlow<List<ConnectionSummary>> = _connections.asStateFlow()

        init {
            combine(
                satellite.connections,
                bt.states,
            ) { satMap, btStates -> buildSummaries(satMap, btStates) }
                .onEach { _connections.value = it }
                .launchIn(scope)
        }

        private fun buildSummaries(
            satMap: Map<String, SatelliteConnection>,
            btStates: Map<String, BluetoothGamepadRegistry.SlotState>,
        ): List<ConnectionSummary> {
            val bindingBySlot = _bindings.value
            val result = mutableListOf<ConnectionSummary>()

            // Satellites: remembered + any live not-yet-remembered (discovery hits).
            val remembered = store.remembered().associateBy { it.id }
            val satIds = (remembered.keys + satMap.keys).toSet()
            for (id in satIds) {
                val conn = satMap[id]
                val server = conn?.server?.value ?: remembered[id]?.toDiscovered() ?: continue
                val live =
                    when (conn?.state?.value) {
                        SatelliteState.CONNECTED -> ConnectionLive.CONNECTED
                        SatelliteState.CONNECTING -> ConnectionLive.CONNECTING
                        else -> ConnectionLive.IDLE
                    }
                val bound = bindingBySlot.entries.firstOrNull { it.value == id }?.key
                result +=
                    ConnectionSummary(
                        id = id,
                        kind = ConnectionKind.SATELLITE,
                        label = server.name.ifEmpty { server.ip },
                        detail = "${server.ip} • UDP ${server.udpPort}",
                        live = live,
                        boundSlotId = bound,
                    )
            }

            // Bluetooth: one summary per remembered host; live state from registry.
            for (entry in store.rememberedBt()) {
                val bound = bindingBySlot.entries.firstOrNull { it.value == entry.id }?.key
                val state = btStates[entry.id]
                val live =
                    when {
                        state?.connected == true -> ConnectionLive.CONNECTED
                        state?.registered == true || state?.autoReconnecting == true -> ConnectionLive.CONNECTING
                        else -> ConnectionLive.IDLE
                    }
                result +=
                    ConnectionSummary(
                        id = entry.id,
                        kind = ConnectionKind.BLUETOOTH,
                        label = entry.name.ifEmpty { entry.mac },
                        detail = "${entry.profileName} • ${entry.mac}",
                        live = live,
                        boundSlotId = bound,
                        btProfile = entry.profileName,
                    )
            }
            return result
        }

        fun summary(id: String): ConnectionSummary? = _connections.value.firstOrNull { it.id == id }

        /** Bind [slotId] to [connectionId]. Evicts any prior binding on either side. */
        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            val current = _bindings.value.toMutableMap()
            // Another slot already holds this connection? Release it and
            // tell the native session so the server sees a clean
            // CONTROLLER_REMOVE before the new slot's CONTROLLER_ADD.
            val priorSlot = current.entries.firstOrNull { it.value == connectionId }?.key
            if (priorSlot != null && priorSlot != slotId) {
                current.remove(priorSlot)
                satellite.get(connectionId)?.detachSlot()
            }
            current[slotId] = connectionId
            _bindings.value = current
            // Re-emit connections so boundSlotId refreshes.
            _connections.value = buildSummaries(satellite.connections.value, bt.states.value)
            // For satellite: attach the controller slot on the server side.
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.attachSlot(slotId, controllerType = 0) }
            }
        }

        fun unbind(slotId: String) {
            val current = _bindings.value.toMutableMap()
            val connId = current.remove(slotId) ?: return
            _bindings.value = current
            satellite.get(connId)?.detachSlot()
            _connections.value = buildSummaries(satellite.connections.value, bt.states.value)
        }

        fun boundConnection(slotId: String): ConnectionSummary? =
            _bindings.value[slotId]?.let { id -> _connections.value.firstOrNull { it.id == id } }

        /**
         * Kick off a reconnect for every remembered satellite that isn't live
         * yet, plus the first remembered Bluetooth host (Android's HID Device
         * profile only supports one active host per app).
         *
         * Safe to call on every `MainActivity.onCreate` — both underlying managers
         * are idempotent when a session is already live.
         */
        fun autoReconnectAll() {
            for (remembered in store.remembered()) {
                val existing = satellite.get(remembered.id)
                if (existing?.state?.value != SatelliteState.CONNECTED) {
                    satellite.connect(remembered.toDiscovered())
                }
            }
            val btHosts = store.rememberedBt()
            // Include `autoReconnecting` so an in-flight acquire (triggered by an
            // earlier foreground kick) isn't torn down and restarted by the next
            // one — the registry is internally idempotent, but we still want to
            // avoid bouncing the SessionState machine.
            if (btHosts.none {
                    val s = bt.state(it.id)
                    s.connected || s.registered || s.autoReconnecting
                }
            ) {
                btHosts.firstOrNull()?.let { bt.tryAutoReconnect(it.id) }
            }
        }
    }
