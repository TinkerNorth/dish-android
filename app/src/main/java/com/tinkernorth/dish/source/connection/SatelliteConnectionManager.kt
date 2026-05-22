// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import android.content.Context
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

/**
 * Why a [connect] attempt was initiated. The same connect path is shared
 * between three callers with very different user-feedback expectations:
 *
 *  - [USER_INITIATED] — user tapped Connect / discovered satellite row.
 *    Failure SHOULD surface a notification (the user just took an action
 *    that's about to silently fail otherwise).
 *  - [AUTO_RECONNECT] — fired on app foreground / cold start by
 *    [ConnectionHub.autoReconnectAll], or after a network-changed event.
 *    Failure MUST be silent: the row chip's natural Connecting → Saved/Stale
 *    flip is the feedback. A toast / banner here would fire on every cold
 *    start the satellite is down — pure noise the user didn't ask for.
 *  - [RETRY_AFTER_DEATH] — fired by the alive-poll's onDead callback after a
 *    short backoff. Same silence policy as [AUTO_RECONNECT]: the chip flips
 *    to "Connecting…" and either recovers or lands on Saved/Stale, both of
 *    which the row renders without a separate notification.
 *
 * The intent is threaded through [pairAndConnect] / [openSession]; every
 * `_events.emit(ConnectionEvent.Error(...))` is gated on it.
 */
enum class ConnectIntent { USER_INITIATED, AUTO_RECONNECT, RETRY_AFTER_DEATH }

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
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        /**
         * `Provider` (not direct injection) breaks the Hilt construction cycle:
         * [MotionCapabilityComposer] depends on [com.tinkernorth.dish.composer.ConnectionHub],
         * which depends back on this manager. Using `Provider.get()` defers
         * resolution until the cap word is actually needed (at controller-add
         * time), at which point the singleton composer already exists.
         */
        private val motionCapabilityProvider: Provider<MotionCapabilityComposer>,
        /**
         * Per-(connection, slot) store the receiver's truth about its motion
         * sink lands in. Written from [SatelliteConnection.registerController]
         * after a successful ACK using the motion-status byte the satellite
         * appends to the ACK payload. The composer reads from it so the pill
         * can surface "satellite kernel rejected the IMU sink" as a distinct
         * state rather than falsely showing STREAMING.
         */
        private val motionBackendStatusStore: SatelliteMotionBackendStatusStore,
    ) {
        private val _connections = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
        val connections: StateFlow<Map<String, SatelliteConnection>> = _connections.asStateFlow()

        private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
        val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        /**
         * Wall-clock timestamp of the most recently completed discovery scan,
         * or null if no scan has run yet this process. Drives the dynamic
         * empty-state copy on Connections so the row never reads the same as
         * "never scanned" + "scanned, found nothing".
         */
        private val _lastScanAtMs = MutableStateFlow<Long?>(null)
        val lastScanAtMs: StateFlow<Long?> = _lastScanAtMs.asStateFlow()

        // Fire-and-forget broadcast: every emission is delivered to currently-
        // active subscribers and then forgotten. replay=0 is critical — a
        // subscriber that pauses and re-subscribes (every activity-switch
        // does this via repeatOnLifecycle(STARTED)) must NOT receive a stale
        // event from a prior session, or the same banner would re-fire on
        // every navigation. All three Dish activities collect events while
        // STARTED, so the only emissions that could be lost are those firing
        // in the microsecond window between one activity stopping and the
        // next starting — vanishingly small.
        //
        // extraBufferCapacity is kept so emit() never suspends: the
        // SatelliteConnection callbacks (onDead, onRegistrationFailed) come
        // from short-lived launches that shouldn't block on a missing
        // subscriber.
        private val _events =
            MutableSharedFlow<ConnectionEvent>(
                replay = 0,
                extraBufferCapacity = 8,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

        // Client-side "Stale" markers. Set when an auto-reconnect's pair
        // handshake comes back with ok=false (server forgot the pairing /
        // rotated keys) — we can't bother the user with a dialog because the
        // attempt wasn't user-initiated, but the row chip needs to read
        // "Needs pairing" rather than "Offline" so the user knows tapping it
        // will prompt for a fresh PIN. Cleared the moment a session opens.
        private val _staleSatelliteIds = MutableStateFlow<Set<String>>(emptySet())
        val staleSatelliteIds: StateFlow<Set<String>> = _staleSatelliteIds.asStateFlow()

        private val deviceId by lazy { getOrCreateDeviceId() }
        private val deviceName by lazy { android.os.Build.MODEL ?: "Android" }

        init {
            // Reactive cap-word refresh — the third leg of the composer-as-
            // single-source-of-truth pattern. UI consumers already read the
            // composer (pill); the listener gate already reads the composer
            // (motionSource.start/stop); now the WIRE cap word also reacts
            // to composer changes, via a per-connection diff against
            // SlotBinding.lastAdvertisedCaps.
            //
            // Why subscribe to `cap-bits-by-slot` (the projection), not
            // `composer.state` directly: the composer's `MotionCapability`
            // carries five fields, only one of which (toCapBits) affects the
            // wire. Without the projection + distinctUntilChanged, every
            // hub.connections emit (which the composer combine forwards)
            // would push a no-op caps-update for every registered slot.
            //
            // Launched in `init`, but the `Provider.get()` doesn't run until
            // the coroutine actually dispatches — at which point construction
            // is complete and the Hilt cycle is resolved (same reason the
            // motionCapsBitsFor lambda uses Provider rather than direct
            // injection — see the Provider's KDoc above).
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

        // ── Discovery ─────────────────────────────────────────────────────────

        fun startDiscovery() {
            // compareAndSet so two concurrent foreground kicks (MainActivity
            // onCreate + ConnectionForegroundObserver) don't both clear the
            // gate and launch parallel scans.
            if (!_isScanning.compareAndSet(expect = false, update = true)) return
            scope.launch {
                val servers = discoveryRepo.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
                _discoveredServers.value = servers
                _lastScanAtMs.value = System.currentTimeMillis()
                _isScanning.value = false
                // No-op on empty: the row list + tvSatelliteEmpty already
                // narrates "scanned, found nothing" via the lastScanAt
                // timestamp. A notification would duplicate that message.
            }
        }

        // ── Connect / Disconnect ──────────────────────────────────────────────

        /**
         * Open or re-open a session to [server]. [intent] decides whether
         * downstream failures emit user-visible notifications:
         *  - [ConnectIntent.USER_INITIATED] — emit on every failure (the user
         *    just acted and would otherwise see no signal).
         *  - [ConnectIntent.AUTO_RECONNECT] / [ConnectIntent.RETRY_AFTER_DEATH]
         *    — emit nothing; the row chip already conveys the result.
         */
        fun connect(
            server: DiscoveredServer,
            intent: ConnectIntent = ConnectIntent.USER_INITIATED,
        ) {
            val id = SatelliteConnection.idFor(server)
            // Atomic find-or-create. Without `update` two concurrent first-time
            // connects to the same id (foreground observer + a user tap landing
            // in the same tick) could each enter the `existing == null` branch
            // and construct competing SatelliteConnection instances; the loser
            // would leak its scope/mutex references until GC.
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
            // Idempotent on live / in-flight sessions: the foreground observer
            // and MainActivity both kick auto-reconnect on every return, so
            // without this guard a CONNECTING session would have its pair/auth
            // flow restarted mid-handshake, leaking sockets and confusing the
            // server.
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
                // If we already have a pair-derived shared key for this server,
                // skip the pairing handshake entirely. This shaves a network
                // round-trip off every auto-reconnect and — more importantly —
                // means a server that's silently moved networks fails fast in
                // openSession with a clean "unreachable" error, instead of
                // bouncing the user back to a pin dialog they can never satisfy
                // (the dialog would re-pair against the dead IP).
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
        }
    }
