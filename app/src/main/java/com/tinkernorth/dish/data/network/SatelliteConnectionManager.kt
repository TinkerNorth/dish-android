// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.Context
import com.tinkernorth.dish.data.model.ConnectResponse
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.model.PairResponse
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConnectionEvent {
    data class PairingRequired(
        val server: DiscoveredServer,
    ) : ConnectionEvent()

    data class Error(
        val message: String,
    ) : ConnectionEvent()
}

/**
 * Owns the pool of live + remembered satellite sessions. Each session runs its
 * own native handle, heartbeat and ACK loop so multiple satellites can be
 * active in parallel. Slots bind to a specific connection via the hub and
 * their input is routed through the bound connection's
 * [SatelliteConnection.sendReport].
 */
@Singleton
class SatelliteConnectionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val scope: CoroutineScope,
        private val discoveryRepo: DiscoveryRepository,
        val controllerRepo: ControllerRepository,
        private val store: ConnectionStore,
        private val json: Json,
    ) {
        private val _connections = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
        val connections: StateFlow<Map<String, SatelliteConnection>> = _connections.asStateFlow()

        private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
        val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _events = MutableSharedFlow<ConnectionEvent>()
        val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

        private val deviceId by lazy { getOrCreateDeviceId() }
        private val deviceName by lazy { android.os.Build.MODEL ?: "Android" }

        fun get(id: String): SatelliteConnection? = _connections.value[id]

        fun remembered(): List<RememberedSatellite> = store.remembered()

        // ── Discovery ─────────────────────────────────────────────────────────

        fun startDiscovery() {
            if (_isScanning.value) return
            _isScanning.value = true
            scope.launch {
                val servers = discoveryRepo.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
                _discoveredServers.value = servers
                _isScanning.value = false
                if (servers.isEmpty()) _events.emit(ConnectionEvent.Error("No satellites found — check your network"))
            }
        }

        // ── Connect / Disconnect ──────────────────────────────────────────────

        fun connect(server: DiscoveredServer) {
            val id = SatelliteConnection.idFor(server)
            val existing = _connections.value[id]
            // Idempotent on live / in-flight sessions: the foreground observer
            // and MainActivity both kick auto-reconnect on every return, so
            // without this guard a CONNECTING session would have its pair/auth
            // flow restarted mid-handshake, leaking sockets and confusing the
            // server.
            if (existing != null) {
                when (existing.state.value) {
                    SessionState.Live,
                    SessionState.Linking,
                    SessionState.Faltering,
                    -> {
                        existing.updateServer(server)
                        return
                    }
                    SessionState.Idle -> Unit
                }
            }
            val conn =
                existing ?: SatelliteConnection(id, server, scope, controllerRepo).also {
                    _connections.value = _connections.value + (id to it)
                }
            conn.updateServer(server)
            conn.markConnecting()
            scope.launch {
                // If we already have a pair-derived shared key for this server,
                // skip the pairing handshake entirely. This shaves a network
                // round-trip off every auto-reconnect and — more importantly —
                // means a server that's silently moved networks fails fast in
                // openSession with a clean "unreachable" error, instead of
                // bouncing the user back to a pin dialog they can never satisfy
                // (the dialog would re-pair against the dead IP).
                if (store.satelliteSharedKey(id) != null) {
                    openSession(conn, server)
                } else {
                    pairAndConnect(conn, server)
                }
            }
        }

        private suspend fun pairAndConnect(
            conn: SatelliteConnection,
            server: DiscoveredServer,
        ) {
            val raw = runCatching { discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, "") }
            val rawText = raw.getOrNull()
            if (raw.isFailure || rawText.isNullOrBlank()) {
                // Empty/exception from native pair() means we couldn't reach the
                // server at all (DNS/connect failure on the LAN). This is the
                // network-move case — surface it clearly instead of routing the
                // user through a pin dialog that will fail against the dead IP.
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                return
            }
            val pair =
                runCatching { json.decodeFromString(PairResponse.serializer(), rawText) }
                    .getOrNull()
            if (pair == null) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                return
            }
            if (!pair.ok || pair.sharedKey == null) {
                // Server reachable but refused the empty PIN — first-time pair
                // or credentials rotated server-side. Prompt for a PIN.
                conn.markDisconnected()
                _events.emit(ConnectionEvent.PairingRequired(server))
                return
            }
            store.setSatelliteSharedKey(SatelliteConnection.idFor(server), pair.sharedKey)
            openSession(conn, server)
        }

        fun pairWithPin(
            server: DiscoveredServer,
            pin: String,
        ) {
            val id = SatelliteConnection.idFor(server)
            val conn =
                _connections.value[id] ?: SatelliteConnection(id, server, scope, controllerRepo).also {
                    _connections.value = _connections.value + (id to it)
                }
            conn.markConnecting()
            scope.launch {
                val raw = runCatching { discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, pin) }
                val rawText = raw.getOrNull()
                if (raw.isFailure || rawText.isNullOrBlank()) {
                    conn.markDisconnected()
                    _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                    return@launch
                }
                val pair =
                    runCatching { json.decodeFromString(PairResponse.serializer(), rawText) }
                        .getOrNull()
                if (pair == null) {
                    conn.markDisconnected()
                    _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                    return@launch
                }
                if (!pair.ok || pair.sharedKey == null) {
                    conn.markDisconnected()
                    _events.emit(ConnectionEvent.Error(pair.error ?: "Pairing failed"))
                    return@launch
                }
                store.setSatelliteSharedKey(SatelliteConnection.idFor(server), pair.sharedKey)
                openSession(conn, server)
            }
        }

        private suspend fun openSession(
            conn: SatelliteConnection,
            server: DiscoveredServer,
        ) = withContext(Dispatchers.IO) {
            val keyHex = sharedKeyFor(server) ?: ""
            if (keyHex.length != 64) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error("No shared key — re-pair needed"))
                return@withContext
            }
            val key = hexToBytes(keyHex)
            val rawConn = runCatching { discoveryRepo.connect(server.ip, server.httpPort, deviceId) }
            val rawConnText = rawConn.getOrNull()
            if (rawConn.isFailure || rawConnText.isNullOrBlank()) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                return@withContext
            }
            val resp =
                runCatching { json.decodeFromString(ConnectResponse.serializer(), rawConnText) }
                    .getOrNull()
            if (resp == null) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                return@withContext
            }
            val connId = resp.connectionId
            val tokenHex = resp.token
            if (connId == null || tokenHex == null) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error("Error: ${resp.error ?: "connection failed"}"))
                return@withContext
            }
            val token = hexToBytes(tokenHex)
            if (token.size != 4 || key.size != 32) {
                conn.markDisconnected()
                return@withContext
            }
            val handle = controllerRepo.openSocket(server.ip, server.udpPort)
            if (handle < 0) {
                conn.markDisconnected()
                return@withContext
            }
            controllerRepo.setConnectionParams(handle, token, key)
            store.rememberSatellite(server)
            conn.markConnected(
                handle,
                connId,
                onDead = { disconnect(conn.id) },
                onRegistrationFailed = { reason ->
                    scope.launch {
                        _events.emit(
                            ConnectionEvent.Error(
                                "Couldn't register controller with ${server.name} — $reason",
                            ),
                        )
                    }
                },
            )
        }

        fun disconnect(id: String) {
            val conn = _connections.value[id] ?: return
            val srv = conn.server.value
            val cid = conn.connectionId
            conn.markDisconnected()
            if (cid != null) {
                scope.launch(Dispatchers.IO) {
                    runCatching { discoveryRepo.disconnect(srv.ip, srv.httpPort, cid, deviceId) }
                }
            }
        }

        fun forget(id: String) {
            disconnect(id)
            store.forgetSatellite(id)
            _connections.value = _connections.value - id
        }

        // ── Prefs ─────────────────────────────────────────────────────────────

        private fun getOrCreateDeviceId(): String {
            val p = context.getSharedPreferences("satellite", Context.MODE_PRIVATE)
            return p.getString("deviceId", null) ?: java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .also { p.edit().putString("deviceId", it).apply() }
        }

        /**
         * Read the pair-derived shared key for [server]. Prefers the per-server
         * entry in [ConnectionStore]; on first run for users upgrading from the
         * pre-per-server-key build, claims the legacy single-slot key (in the
         * `satellite` prefs file) for this server and clears the legacy slot so
         * no other server can inherit it later. Returns null if no key is known.
         */
        private fun sharedKeyFor(server: DiscoveredServer): String? {
            val id = SatelliteConnection.idFor(server)
            store.satelliteSharedKey(id)?.let { return it }
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val legacy = legacyPrefs.getString(LEGACY_KEY_SHARED, null) ?: return null
            store.setSatelliteSharedKey(id, legacy)
            legacyPrefs.edit().remove(LEGACY_KEY_SHARED).apply()
            return legacy
        }

        companion object {
            private const val DISC_PORT = 9879
            private const val DISC_TIMEOUT_MS = 4000
            private const val LEGACY_PREFS = "satellite"
            private const val LEGACY_KEY_SHARED = "sharedKey"
            internal const val SERVER_UNREACHABLE_MSG =
                "Server unreachable — has it moved networks? Forget and re-add."
        }
    }
