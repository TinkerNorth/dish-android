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

/** Server-visible controller type. The native protocol carries the raw int. */
const val CONTROLLER_TYPE_XBOX = 0
const val CONTROLLER_TYPE_PLAYSTATION = 1

/**
 * Snapshot of a single remembered or live connection, used by the UI to render
 * the connections list and the bind-to-connection picker on each slot.
 *
 * [boundSlotIds] is a list because a satellite session can host multiple
 * controllers; for Bluetooth the hub enforces at most one entry.
 */
data class ConnectionSummary(
    val id: String,
    val kind: ConnectionKind,
    val label: String,
    val detail: String,
    val live: ConnectionLive,
    val boundSlotIds: List<String>,
    /** For BT only: name of the remembered [BluetoothGamepad.GamepadProfile]. */
    val btProfile: String? = null,
    /**
     * For SATELLITE only: per-slot controller type (Xbox / PlayStation /…).
     * Empty for Bluetooth since BT type is fixed by the remembered host's
     * HID profile.
     */
    val satelliteControllerTypes: Map<String, Int> = emptyMap(),
)

/**
 * Single place every slot talks to. Aggregates [SatelliteConnectionManager] and
 * [BluetoothGamepadRegistry] into one stream of [ConnectionSummary] entries.
 *
 * Binding a slot to a connection is a UI-level concept (which slot routes its
 * input where) — the actual wiring lives in the managers; the hub just tracks
 * the bindings. Eviction is per-kind: Bluetooth is capped at one slot per
 * connection (HID Device profile only supports a single host) but satellites
 * can host as many slots as the server allows.
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

        /**
         * (connectionId, slotId) -> controller type for satellite bindings.
         * Source of truth for the UI's per-slot Xbox/PS toggle; the actual
         * push to the native session goes through [SatelliteConnection.setControllerType].
         * Bluetooth's type is fixed by the remembered host so it doesn't live here.
         */
        private val _satTypes = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
        val satTypes: StateFlow<Map<Pair<String, String>, Int>> = _satTypes.asStateFlow()

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
            val bindings = _bindings.value
            val satTypes = _satTypes.value
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
                val bound = bindings.entries.filter { it.value == id }.map { it.key }
                val typesForConn = buildSlotTypes(id, bound, satTypes)
                result +=
                    ConnectionSummary(
                        id = id,
                        kind = ConnectionKind.SATELLITE,
                        label = server.name.ifEmpty { server.ip },
                        detail = "${server.ip} • UDP ${server.udpPort}",
                        live = live,
                        boundSlotIds = bound,
                        satelliteControllerTypes = typesForConn,
                    )
            }

            // Bluetooth: one summary per remembered host; live state from registry.
            val rememberedBtIds = mutableSetOf<String>()
            for (entry in store.rememberedBt()) {
                rememberedBtIds += entry.id
                val bound = bindings.entries.filter { it.value == entry.id }.map { it.key }
                val state = btStates[entry.id]
                val live =
                    when {
                        state?.connected == true -> ConnectionLive.CONNECTED
                        state?.registered == true ||
                            state?.autoReconnecting == true ||
                            state?.acquiring == true -> ConnectionLive.CONNECTING
                        else -> ConnectionLive.IDLE
                    }
                result +=
                    ConnectionSummary(
                        id = entry.id,
                        kind = ConnectionKind.BLUETOOTH,
                        label = entry.name.ifEmpty { entry.mac },
                        detail = "${entry.profileName} • ${entry.mac}",
                        live = live,
                        boundSlotIds = bound,
                        btProfile = entry.profileName,
                    )
            }

            // Transient BT slots: a registration that hasn't completed yet, so
            // the host MAC is unknown and the entry isn't in rememberedBt yet.
            // Surface them so the user sees progress between "tap profile" and
            // "host paired". On success the registry re-keys to bt:<MAC> and
            // the next emission collapses this row into the remembered one.
            for ((id, state) in btStates) {
                if (id in rememberedBtIds) continue
                val live =
                    when {
                        state.connected -> ConnectionLive.CONNECTED
                        state.registered || state.autoReconnecting || state.acquiring -> ConnectionLive.CONNECTING
                        else -> ConnectionLive.IDLE
                    }
                val detail =
                    when {
                        state.connected -> state.connectedName.orEmpty()
                        state.registered -> "Ready to pair — find this device on your host"
                        state.acquiring || state.autoReconnecting -> "Acquiring HID profile…"
                        else -> "Idle"
                    }
                result +=
                    ConnectionSummary(
                        id = id,
                        kind = ConnectionKind.BLUETOOTH,
                        label = state.profileName ?: "Bluetooth gamepad",
                        detail = detail,
                        live = live,
                        boundSlotIds = bindings.entries.filter { it.value == id }.map { it.key },
                        btProfile = state.profileName,
                    )
            }
            return result
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

        fun summary(id: String): ConnectionSummary? = _connections.value.firstOrNull { it.id == id }

        /**
         * Bind [slotId] to [connectionId]. Eviction depends on the connection
         * kind: Bluetooth releases any prior slot on the same host (single-host
         * HID Device profile constraint), but satellites accept multiple slots
         * side-by-side. If the slot was previously bound elsewhere, that prior
         * binding is detached first regardless of kind.
         */
        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            val current = _bindings.value.toMutableMap()

            // Slot was previously bound to a different connection: release it
            // there before claiming the new one. Without this the old satellite
            // session would keep treating this slot as its owner and reports
            // for it would still be addressed to the old controller index.
            val priorConnId = current[slotId]
            if (priorConnId != null && priorConnId != connectionId) {
                satellite.get(priorConnId)?.detachSlot(slotId)
                _satTypes.value = _satTypes.value - (priorConnId to slotId)
            }

            // Bluetooth-only: another slot already holds this connection?
            // Release it and tell the native session so the server sees a
            // clean CONTROLLER_REMOVE before the new slot's CONTROLLER_ADD.
            // Satellites are multi-slot so we leave existing bindings alone.
            val isBt = store.rememberedBt().any { it.id == connectionId }
            if (isBt) {
                val priorSlot = current.entries.firstOrNull { it.value == connectionId && it.key != slotId }?.key
                if (priorSlot != null) {
                    current.remove(priorSlot)
                    satellite.get(connectionId)?.detachSlot(priorSlot)
                }
            }

            current[slotId] = connectionId
            _bindings.value = current

            // Stash a default type for new satellite bindings so the UI's
            // per-slot toggle has something to render before the user picks.
            val type = _satTypes.value[connectionId to slotId] ?: CONTROLLER_TYPE_XBOX
            if (!isBt) {
                _satTypes.value = _satTypes.value + ((connectionId to slotId) to type)
            }

            // Re-emit connections so boundSlotIds refreshes.
            _connections.value = buildSummaries(satellite.connections.value, bt.states.value)
            // For satellite: attach the controller slot on the server side.
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.attachSlot(slotId, controllerType = type) }
            }
        }

        fun unbind(slotId: String) {
            val current = _bindings.value.toMutableMap()
            val connId = current.remove(slotId) ?: return
            _bindings.value = current
            satellite.get(connId)?.detachSlot(slotId)
            _satTypes.value = _satTypes.value - (connId to slotId)
            _connections.value = buildSummaries(satellite.connections.value, bt.states.value)
        }

        /**
         * Change the controller type (Xbox/PS/…) for a satellite-bound slot.
         * Pushes the change to the native session if it's live; otherwise
         * stashes the new value so it's used at the next registration.
         * No-op for Bluetooth bindings since BT type is fixed by the
         * remembered host's HID profile.
         */
        fun setSatelliteControllerType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            val key = connectionId to slotId
            if (_satTypes.value[key] == type) return
            _satTypes.value = _satTypes.value + (key to type)
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.setControllerType(slotId, type) }
            }
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
