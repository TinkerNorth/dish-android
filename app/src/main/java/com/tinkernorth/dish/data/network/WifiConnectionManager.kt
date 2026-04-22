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
 * Owns the pool of live + remembered WiFi sessions. Each session runs its own
 * native handle, heartbeat and ACK loop so multiple servers can be active in
 * parallel. Slots bind to a specific connection via the hub and their input is
 * routed through the bound connection's [WifiConnection.sendReport].
 */
@Singleton
class WifiConnectionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val scope: CoroutineScope,
        private val discoveryRepo: DiscoveryRepository,
        val controllerRepo: ControllerRepository,
        private val store: ConnectionStore,
        private val json: Json,
    ) {
        private val _connections = MutableStateFlow<Map<String, WifiConnection>>(emptyMap())
        val connections: StateFlow<Map<String, WifiConnection>> = _connections.asStateFlow()

        private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
        val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _events = MutableSharedFlow<ConnectionEvent>()
        val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

        private val deviceId by lazy { getOrCreateDeviceId() }
        private val deviceName by lazy { android.os.Build.MODEL ?: "Android" }

        fun get(id: String): WifiConnection? = _connections.value[id]

        fun remembered(): List<RememberedWifi> = store.remembered()

        // ── Discovery ─────────────────────────────────────────────────────────

        fun startDiscovery() {
            if (_isScanning.value) return
            _isScanning.value = true
            scope.launch {
                val servers = discoveryRepo.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
                _discoveredServers.value = servers
                _isScanning.value = false
                if (servers.isEmpty()) _events.emit(ConnectionEvent.Error("No servers found — check your network"))
            }
        }

        // ── Connect / Disconnect ──────────────────────────────────────────────

        fun connect(server: DiscoveredServer) {
            val id = WifiConnection.idFor(server)
            val existing = _connections.value[id]
            // Idempotent on live / in-flight sessions: the foreground observer
            // and MainActivity both kick auto-reconnect on every return, so
            // without this guard a CONNECTING session would have its pair/auth
            // flow restarted mid-handshake, leaking sockets and confusing the
            // server.
            if (existing != null) {
                when (existing.state.value) {
                    WifiState.CONNECTED,
                    WifiState.CONNECTING,
                    -> {
                        existing.updateServer(server)
                        return
                    }
                    WifiState.IDLE -> Unit
                }
            }
            val conn =
                existing ?: WifiConnection(id, server, scope, controllerRepo).also {
                    _connections.value = _connections.value + (id to it)
                }
            conn.updateServer(server)
            conn.markConnecting()
            scope.launch { pairAndConnect(conn, server) }
        }

        private suspend fun pairAndConnect(
            conn: WifiConnection,
            server: DiscoveredServer,
        ) {
            val pair =
                runCatching {
                    json.decodeFromString(
                        PairResponse.serializer(),
                        discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, ""),
                    )
                }.getOrElse { PairResponse(error = "Malformed pair response") }

            if (!pair.ok || pair.sharedKey == null) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.PairingRequired(server))
                return
            }
            store.setWifiSharedKey(WifiConnection.idFor(server), pair.sharedKey)
            openSession(conn, server)
        }

        fun pairWithPin(
            server: DiscoveredServer,
            pin: String,
        ) {
            val id = WifiConnection.idFor(server)
            val conn =
                _connections.value[id] ?: WifiConnection(id, server, scope, controllerRepo).also {
                    _connections.value = _connections.value + (id to it)
                }
            conn.markConnecting()
            scope.launch {
                val pair =
                    runCatching {
                        json.decodeFromString(
                            PairResponse.serializer(),
                            discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, pin),
                        )
                    }.getOrElse { PairResponse(error = "Malformed pair response") }
                if (!pair.ok || pair.sharedKey == null) {
                    conn.markDisconnected()
                    _events.emit(ConnectionEvent.Error(pair.error ?: "Pairing failed"))
                    return@launch
                }
                store.setWifiSharedKey(WifiConnection.idFor(server), pair.sharedKey)
                openSession(conn, server)
            }
        }

        private suspend fun openSession(
            conn: WifiConnection,
            server: DiscoveredServer,
        ) = withContext(Dispatchers.IO) {
            val keyHex = sharedKeyFor(server) ?: ""
            if (keyHex.length != 64) {
                conn.markDisconnected()
                _events.emit(ConnectionEvent.Error("No shared key — re-pair needed"))
                return@withContext
            }
            val key = hexToBytes(keyHex)
            val resp =
                runCatching {
                    json.decodeFromString(
                        ConnectResponse.serializer(),
                        discoveryRepo.connect(server.ip, server.httpPort, deviceId),
                    )
                }.getOrElse { ConnectResponse(error = "Malformed connect response") }
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
            store.rememberWifi(server)
            conn.markConnected(handle, connId) { disconnect(conn.id) }
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
            store.forgetWifi(id)
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
            val id = WifiConnection.idFor(server)
            store.wifiSharedKey(id)?.let { return it }
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val legacy = legacyPrefs.getString(LEGACY_KEY_SHARED, null) ?: return null
            store.setWifiSharedKey(id, legacy)
            legacyPrefs.edit().remove(LEGACY_KEY_SHARED).apply()
            return legacy
        }

        companion object {
            private const val DISC_PORT = 9879
            private const val DISC_TIMEOUT_MS = 4000
            private const val LEGACY_PREFS = "satellite"
            private const val LEGACY_KEY_SHARED = "sharedKey"
        }
    }
