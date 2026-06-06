// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.content.Context
import androidx.core.content.edit
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.ConnectResponse
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.PairResponse
import com.tinkernorth.dish.core.net.DiscoveryRepository
import com.tinkernorth.dish.core.net.hexToBytes
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// USER_INITIATED surfaces failures; the silent intents rely on the row chip for feedback.
enum class ConnectIntent { USER_INITIATED, AUTO_RECONNECT, RETRY_AFTER_DEATH }

sealed class ConnectionEvent {
    data class PairingRequired(
        val server: DiscoveredServer,
    ) : ConnectionEvent()

    data class Error(
        val message: String,
    ) : ConnectionEvent()
}

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
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        // Provider (not direct injection) breaks the Hilt cycle: composer → hub → this manager.
        private val motionCapabilityProvider: Provider<MotionCapabilityComposer>,
        private val motionBackendStatusStore: SatelliteMotionBackendStatusStore,
    ) {
        private val _connections = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
        val connections: StateFlow<Map<String, SatelliteConnection>> = _connections.asStateFlow()

        private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
        val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _lastScanAtMs = MutableStateFlow<Long?>(null)
        val lastScanAtMs: StateFlow<Long?> = _lastScanAtMs.asStateFlow()

        // replay=0 so activity-switch re-subscribers don't replay stale banners; buffer keeps emit non-suspending.
        private val _events =
            MutableSharedFlow<ConnectionEvent>(
                replay = 0,
                extraBufferCapacity = 8,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

        private val _staleSatelliteIds = MutableStateFlow<Set<String>>(emptySet())
        val staleSatelliteIds: StateFlow<Set<String>> = _staleSatelliteIds.asStateFlow()

        private val deviceId by lazy { getOrCreateDeviceId() }
        private val deviceName by lazy { android.os.Build.MODEL ?: "Android" }

        init {
            // Project to cap-bits-by-slot so unrelated composer emissions don't fire no-op wire updates.
            scope.launch {
                motionCapabilityProvider
                    .get()
                    .state
                    .map { caps -> caps.mapValues { (_, mc) -> mc.toCapBits() } }
                    .distinctUntilChanged()
                    .collect {
                        _connections.value.values.forEach { conn ->
                            conn.refreshCapsIfChanged()
                        }
                    }
            }
        }

        fun get(id: String): SatelliteConnection? = _connections.value[id]

        fun remembered(): List<RememberedSatellite> = store.remembered()

        fun startDiscovery() {
            if (!_isScanning.compareAndSet(expect = false, update = true)) return
            scope.launch {
                val servers = discoveryRepo.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
                _discoveredServers.value = servers
                _lastScanAtMs.value = System.currentTimeMillis()
                _isScanning.value = false
            }
        }

        fun connect(
            server: DiscoveredServer,
            intent: ConnectIntent = ConnectIntent.USER_INITIATED,
        ) {
            val id = SatelliteConnection.idFor(server)
            // Atomic find-or-create: prevents two concurrent first-time connects allocating duplicates.
            var created: SatelliteConnection? = null
            val conn =
                _connections
                    .updateAndGet { map ->
                        val cur = map[id]
                        if (cur != null) return@updateAndGet map
                        val fresh =
                            SatelliteConnection(
                                id,
                                server,
                                scope,
                                controllerRepo,
                                ioDispatcher,
                                motionCapsBitsFor = { slotId ->
                                    motionCapabilityProvider.get().capabilityFor(slotId).toCapBits()
                                },
                                motionBackendStatusStore = motionBackendStatusStore,
                            )
                        created = fresh
                        map + (id to fresh)
                    }[id] ?: return
            // Idempotent on live/in-flight: foreground kicks must not restart pair/auth mid-handshake.
            if (created == null) {
                when (conn.state.value) {
                    SatelliteSessionState.Live,
                    SatelliteSessionState.Linking,
                    SatelliteSessionState.Faltering,
                    -> {
                        conn.updateServer(server)
                        return
                    }
                    SatelliteSessionState.Idle -> Unit
                }
            }
            conn.updateServer(server)
            conn.markConnecting()
            scope.launch {
                // Skip pair handshake if we have a shared key — failure surfaces as unreachable, not bogus PIN dialog.
                if (store.satelliteSharedKey(id) != null) {
                    openSession(conn, server, intent)
                } else {
                    pairAndConnect(conn, server, intent)
                }
            }
        }

        private suspend fun pairAndConnect(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
        ) {
            val id = SatelliteConnection.idFor(server)
            val raw = runCatching { discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, "") }
            val rawText = raw.getOrNull()
            if (raw.isFailure || rawText.isNullOrBlank()) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                return
            }
            val pair =
                runCatching { json.decodeFromString(PairResponse.serializer(), rawText) }
                    .getOrNull()
            if (pair == null) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                return
            }
            if (!pair.ok || pair.sharedKey == null) {
                // Server reachable but refused the empty PIN. This is the
                // "server forgot us" / "first-time pair" branch.
                conn.markDisconnected()
                when (intent) {
                    ConnectIntent.USER_INITIATED ->
                        // User just tapped Connect on this satellite — prompt
                        // them for a PIN immediately.
                        _events.emit(ConnectionEvent.PairingRequired(server))
                    ConnectIntent.AUTO_RECONNECT,
                    ConnectIntent.RETRY_AFTER_DEATH,
                    ->
                        // Don't pop a dialog the user didn't ask for. Mark the
                        // row as Stale so the chip reads "Needs pairing" and
                        // the next user-initiated Connect tap promotes them to
                        // the dialog cleanly. Also clear the bogus shared key
                        // we might be carrying so a future auto-reconnect
                        // doesn't retry the same failing handshake.
                        markStale(id)
                }
                return
            }
            // Success path: any prior Stale marker no longer applies.
            clearStale(id)
            store.setSatelliteSharedKey(id, pair.sharedKey)
            openSession(conn, server, intent)
        }

        fun pairWithPin(
            server: DiscoveredServer,
            pin: String,
        ) {
            val id = SatelliteConnection.idFor(server)
            // Same atomic find-or-create as connect(): two concurrent PIN
            // submissions on the same satellite must not race to allocate
            // duplicate SatelliteConnection instances.
            val conn =
                _connections
                    .updateAndGet { map ->
                        if (map.containsKey(id)) return@updateAndGet map
                        map + (
                            id to
                                SatelliteConnection(
                                    id,
                                    server,
                                    scope,
                                    controllerRepo,
                                    ioDispatcher,
                                    motionCapsBitsFor = { slotId ->
                                        motionCapabilityProvider.get().capabilityFor(slotId).toCapBits()
                                    },
                                    motionBackendStatusStore = motionBackendStatusStore,
                                )
                        )
                    }[id] ?: return
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
                clearStale(id)
                store.setSatelliteSharedKey(id, pair.sharedKey)
                openSession(conn, server, ConnectIntent.USER_INITIATED)
            }
        }

        /**
         * Path B: the dish shows [clientPin] and asks the operator to accept it
         * on the satellite. Submits the request, then polls /api/pair/status
         * until the operator accepts (→ open the session), declines, or the
         * request times out. The shared key arrives via the poll, never typed on
         * this device.
         */
        fun requestApproval(
            server: DiscoveredServer,
            clientPin: String,
        ) {
            val id = SatelliteConnection.idFor(server)
            val conn =
                _connections
                    .updateAndGet { map ->
                        if (map.containsKey(id)) return@updateAndGet map
                        map + (
                            id to
                                SatelliteConnection(
                                    id,
                                    server,
                                    scope,
                                    controllerRepo,
                                    ioDispatcher,
                                    motionCapsBitsFor = { slotId ->
                                        motionCapabilityProvider.get().capabilityFor(slotId).toCapBits()
                                    },
                                    motionBackendStatusStore = motionBackendStatusStore,
                                )
                        )
                    }[id] ?: return
            conn.updateServer(server)
            conn.markConnecting()
            scope.launch {
                val raw =
                    runCatching {
                        discoveryRepo.pair(
                            server.ip,
                            server.pairPort,
                            deviceId,
                            deviceName,
                            pin = "",
                            clientPin = clientPin,
                        )
                    }.getOrNull()
                if (raw.isNullOrBlank()) {
                    conn.markDisconnected()
                    _events.emit(ConnectionEvent.Error(SERVER_UNREACHABLE_MSG))
                    return@launch
                }
                // Already-paired short-circuit hands a key straight back — no operator step.
                val immediate =
                    runCatching { json.decodeFromString(PairResponse.serializer(), raw) }.getOrNull()
                if (immediate?.ok == true && immediate.sharedKey != null) {
                    clearStale(id)
                    store.setSatelliteSharedKey(id, immediate.sharedKey)
                    openSession(conn, server, ConnectIntent.USER_INITIATED)
                    return@launch
                }
                // Otherwise wait for the operator: poll until accept / deny / timeout.
                // The satellite-PIN path (pairWithPin) shares this connection, so
                // once it reaches Live we bail without touching it — otherwise this
                // poll's terminal paths (esp. the timeout) would tear down a live
                // session the user just established by typing the PIN.
                var waited = 0L
                while (waited < APPROVAL_TIMEOUT_MS) {
                    if (conn.state.value == SatelliteSessionState.Live) return@launch
                    kotlinx.coroutines.delay(APPROVAL_POLL_INTERVAL_MS)
                    waited += APPROVAL_POLL_INTERVAL_MS
                    val statusRaw =
                        runCatching { discoveryRepo.pairStatus(server.ip, server.httpPort, deviceId) }
                            .getOrNull()
                    // A transient null reply is treated as still-pending, not a refusal.
                    val st =
                        if (statusRaw.isNullOrBlank()) {
                            PairingApproval.Status.Pending
                        } else {
                            PairingApproval.classifyStatus(statusRaw)
                        }
                    // Re-check: Live may have flipped during the poll round-trip.
                    if (conn.state.value == SatelliteSessionState.Live) return@launch
                    if (st is PairingApproval.Status.Approved) {
                        clearStale(id)
                        store.setSatelliteSharedKey(id, st.sharedKeyHex)
                        openSession(conn, server, ConnectIntent.USER_INITIATED)
                        return@launch
                    }
                    if (st is PairingApproval.Status.Declined) {
                        conn.markDisconnected()
                        _events.emit(ConnectionEvent.Error(APPROVAL_DECLINED_MSG))
                        return@launch
                    }
                }
                if (conn.state.value != SatelliteSessionState.Live) {
                    conn.markDisconnected()
                    _events.emit(ConnectionEvent.Error(APPROVAL_TIMEOUT_MSG))
                }
            }
        }

        private suspend fun openSession(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
        ) = withContext(ioDispatcher) {
            val id = SatelliteConnection.idFor(server)
            val keyHex = sharedKeyFor(server) ?: ""
            if (keyHex.length != 64) {
                conn.markDisconnected()
                // The local key is bogus — drop it so a subsequent
                // user-initiated Connect goes through the pair handshake fresh
                // rather than re-trying with the same broken value.
                store.forgetSatelliteSharedKey(id)
                markStale(id)
                emitErrorIfUserInitiated(intent, "No shared key — re-pair needed")
                return@withContext
            }
            val key = hexToBytes(keyHex)
            val rawConn = runCatching { discoveryRepo.connect(server.ip, server.httpPort, deviceId) }
            val rawConnText = rawConn.getOrNull()
            if (rawConn.isFailure || rawConnText.isNullOrBlank()) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                return@withContext
            }
            val resp =
                runCatching { json.decodeFromString(ConnectResponse.serializer(), rawConnText) }
                    .getOrNull()
            if (resp == null) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                return@withContext
            }
            val connId = resp.connectionId
            val tokenHex = resp.token
            if (connId == null || tokenHex == null) {
                conn.markDisconnected()
                // Auth-shape failure: the server rejected our token-issue
                // request with a body. The most common cause is that the
                // server has forgotten this device's shared key, so clear
                // ours too and mark Stale so the next user tap re-pairs
                // cleanly instead of re-running the same dead handshake.
                if (looksLikeAuthFailure(resp.error)) {
                    store.forgetSatelliteSharedKey(id)
                    markStale(id)
                }
                emitErrorIfUserInitiated(
                    intent,
                    "Error: ${resp.error ?: "connection failed"}",
                )
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
            // Successful session: any Stale marker we set on the way here no
            // longer applies, and the chip flips Live → Online.
            clearStale(id)
            conn.markConnected(
                handle,
                connId,
                onDead = {
                    // Silent auto-retry — the row chip handles the user-visible
                    // narrative ("Connecting…" → "Online"/"Offline"/"Needs pairing").
                    disconnect(conn.id)
                    scope.launch {
                        kotlinx.coroutines.delay(AUTO_RETRY_BACKOFF_MS)
                        if (_connections.value[conn.id]?.state?.value == SatelliteSessionState.Idle) {
                            connect(server, ConnectIntent.RETRY_AFTER_DEATH)
                        }
                    }
                },
                onRegistrationFailed = { reason ->
                    scope.launch {
                        // Registration failure is always notification-worthy
                        // even on auto-reconnect: the session looks Online but
                        // input silently drops, which is invisible state the
                        // user must be told about.
                        _events.emit(
                            ConnectionEvent.Error(
                                "Couldn't register controller with ${server.name} — $reason",
                            ),
                        )
                    }
                },
            )
        }

        /**
         * Emit a [ConnectionEvent.Error] only if the user has a recent
         * mental model for "I asked for this". On AUTO_RECONNECT /
         * RETRY_AFTER_DEATH the row chip carries all the feedback the user
         * needs (Connecting → Saved/Stale/Online) and a notification on top
         * would be noise the user didn't ask for.
         */
        private suspend fun emitErrorIfUserInitiated(
            intent: ConnectIntent,
            message: String,
        ) {
            if (intent == ConnectIntent.USER_INITIATED) {
                _events.emit(ConnectionEvent.Error(message))
            }
        }

        private fun markStale(id: String) {
            _staleSatelliteIds.update { if (id in it) it else it + id }
        }

        private fun clearStale(id: String) {
            _staleSatelliteIds.update { if (id in it) it - id else it }
        }

        /**
         * Heuristic for "this connect-response error means our shared key
         * is no longer recognized by the server". Used to decide whether
         * to drop the local key + flip to Stale. Conservative: an exact
         * server-text match would be brittle, so we match a small set of
         * substrings the satellite uses in its 401-ish responses.
         */
        private fun looksLikeAuthFailure(error: String?): Boolean {
            val e = error?.lowercase() ?: return false
            return "unauthor" in e ||
                "forbidden" in e ||
                "unknown device" in e ||
                "pairing" in e ||
                "invalid token" in e
        }

        fun disconnect(id: String) {
            val conn = _connections.value[id] ?: return
            val srv = conn.server.value
            val cid = conn.connectionId
            conn.markDisconnected()
            if (cid != null) {
                scope.launch(ioDispatcher) {
                    runCatching { discoveryRepo.disconnect(srv.ip, srv.httpPort, cid, deviceId) }
                }
            }
        }

        fun forget(id: String) {
            disconnect(id)
            store.forgetSatellite(id)
            clearStale(id)
            _connections.update { it - id }
        }

        // ── Touchpad routing ──────────────────────────────────────────────────

        /**
         * Push the client-side touchpad-mode pick to the satellite owning
         * [connectionId]. Server hot-applies to the live session AND persists
         * the choice per-device, so a re-connect resumes the same routing
         * without a follow-up round-trip. The raw JSON body is returned so the
         * caller can distinguish `{"ok":true,…}` from `{"error":"…"}` (e.g. a
         * macOS receiver answering 409 to a `ds4` pick because its only
         * advertised mode is `off`).
         *
         * No-op (returns `{"error":"…"}` JSON) if the connection isn't known
         * locally — the typical call site only invokes this for a satellite the
         * user just picked a mode on, so an unknown id signals a UI race we
         * surface rather than swallow silently.
         */
        suspend fun setTouchpadMode(
            connectionId: String,
            mode: String,
        ): String {
            val conn =
                _connections.value[connectionId]
                    ?: return """{"error":"unknown connection $connectionId"}"""
            val server = conn.server.value
            return discoveryRepo.setTouchpadMode(server.ip, server.httpPort, deviceId, mode)
        }

        // ── Prefs ─────────────────────────────────────────────────────────────

        private fun getOrCreateDeviceId(): String {
            val p = context.getSharedPreferences("satellite", Context.MODE_PRIVATE)
            return p.getString("deviceId", null) ?: java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .also { p.edit { putString("deviceId", it) } }
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
            // Upgrade migration: a satellite that just began advertising a
            // machineId flips its id from satellite:<ip>:<udpPort> to
            // satellite:mid:<id>. Claim the key stored under the old ip:port id
            // so a known device isn't bounced through pairing on the first
            // post-upgrade connect.
            if (server.machineId.isNotBlank()) {
                val legacyId = "satellite:${server.ip}:${server.udpPort}"
                store.satelliteSharedKey(legacyId)?.let {
                    store.setSatelliteSharedKey(id, it)
                    return it
                }
            }
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val legacy = legacyPrefs.getString(LEGACY_KEY_SHARED, null) ?: return null
            store.setSatelliteSharedKey(id, legacy)
            legacyPrefs.edit { remove(LEGACY_KEY_SHARED) }
            return legacy
        }

        companion object {
            private const val DISC_PORT = 9879
            private const val DISC_TIMEOUT_MS = 4000
            private const val LEGACY_PREFS = "satellite"
            private const val LEGACY_KEY_SHARED = "sharedKey"

            /**
             * Delay before the alive-poll's onDead path attempts a silent
             * reconnect. Short enough that a momentary Wi-Fi drop self-heals
             * before the user navigates away in frustration; long enough that
             * a real outage doesn't burn the satellite's TCP/UDP buffers with
             * back-to-back retries. The retry path uses
             * [ConnectIntent.RETRY_AFTER_DEATH] so it's silent on failure —
             * the user sees one "Connecting…" flicker, not a notification.
             */
            private const val AUTO_RETRY_BACKOFF_MS = 1500L

            internal const val SERVER_UNREACHABLE_MSG =
                "Server unreachable — check it's powered on and on the same Wi-Fi."

            private const val APPROVAL_POLL_INTERVAL_MS = 2000L

            // Matches the satellite's 2-minute pairing-request TTL — stop waiting
            // once the request can no longer be accepted on the other side.
            private const val APPROVAL_TIMEOUT_MS = 120_000L

            internal const val APPROVAL_DECLINED_MSG =
                "The satellite declined the pairing request."
            internal const val APPROVAL_TIMEOUT_MSG =
                "No response from the satellite — the pairing request timed out."
        }
    }
