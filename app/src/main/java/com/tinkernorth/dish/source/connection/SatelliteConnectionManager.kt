// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.content.Context
import androidx.core.content.edit
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.ControllerPutResponse
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.PairResponse
import com.tinkernorth.dish.core.model.SessionResponse
import com.tinkernorth.dish.core.model.SessionViewDto
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.core.net.SessionCrypto
import com.tinkernorth.dish.core.net.hexToBytes
import com.tinkernorth.dish.core.net.isPrivateHostLiteral
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        private val discoveryRepo: DiscoveryGateway,
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

        // Path-B approval polls run up to APPROVAL_TIMEOUT_MS and can call openSession
        // long after the user forgot the satellite. Keyed by id so disconnect/forget
        // (and a re-issued request) can cancel the in-flight poll.
        private val approvalPollJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

        // Consecutive silent-retry count per satellite id — drives the
        // exponential backoff. Reset on a successful session or any user action.
        private val retryAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        // Single-flight reconcile guard per id: heartbeat ticks fire every
        // second, the reconcile round-trip can take longer.
        private val reconcileInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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

        private fun newConnection(
            id: String,
            server: DiscoveredServer,
        ): SatelliteConnection =
            SatelliteConnection(
                id,
                server,
                scope,
                controllerRepo,
                ioDispatcher = ioDispatcher,
                motionCapsBitsFor = { slotId ->
                    motionCapabilityProvider.get().capabilityFor(slotId).toCapBits()
                },
                motionBackendStatusStore = motionBackendStatusStore,
                onSlotChanged = { slotId -> scope.launch(ioDispatcher) { syncSlot(id, slotId) } },
                onSlotRemoved = { ctrlIdx -> scope.launch(ioDispatcher) { deleteSlot(id, ctrlIdx) } },
            )

        private fun findOrCreate(
            id: String,
            server: DiscoveredServer,
        ): Pair<SatelliteConnection, Boolean> {
            var created = false
            val conn =
                _connections
                    .updateAndGet { map ->
                        if (map.containsKey(id)) return@updateAndGet map
                        created = true
                        map + (id to newConnection(id, server))
                    }[id]!!
            return conn to created
        }

        fun connect(
            server: DiscoveredServer,
            intent: ConnectIntent = ConnectIntent.USER_INITIATED,
        ) {
            val id = SatelliteConnection.idFor(server)
            if (intent == ConnectIntent.USER_INITIATED) retryAttempts.remove(id)
            // Atomic find-or-create: prevents two concurrent first-time connects allocating duplicates.
            val (conn, created) = findOrCreate(id, server)
            // Idempotent on live/in-flight: foreground kicks must not restart pair/auth mid-handshake.
            if (!created) {
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
                // Skip pair handshake if we have a pairing key — failure surfaces as unreachable, not bogus PIN dialog.
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
                // Reachable but no key and no PIN sent: first-time pair / server forgot us.
                conn.markDisconnected()
                when (intent) {
                    ConnectIntent.USER_INITIATED ->
                        _events.emit(ConnectionEvent.PairingRequired(server))
                    ConnectIntent.AUTO_RECONNECT,
                    ConnectIntent.RETRY_AFTER_DEATH,
                    ->
                        // No unsolicited dialog: the Stale chip reads "Needs pairing"
                        // and the next user tap promotes to the PIN dialog.
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
            retryAttempts.remove(id)
            // Atomic find-or-create (as in connect): concurrent PIN submits must not
            // allocate duplicates, and a submit must not stack on a live session.
            val (conn, _) = findOrCreate(id, server)
            if (conn.state.value == SatelliteSessionState.Live) return
            conn.updateServer(server)
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
         * Path B: the dish shows [clientPin]; the operator accepts it on the
         * satellite. Submit, then poll /api/pair/status until accept (→ open
         * the session), decline, or timeout — the pairing key only arrives via
         * the poll. An already-paired device never lands here: connect()
         * routes a keyed satellite straight to the session PUT.
         */
        fun requestApproval(
            server: DiscoveredServer,
            clientPin: String,
        ) {
            val id = SatelliteConnection.idFor(server)
            retryAttempts.remove(id)
            val (conn, _) = findOrCreate(id, server)
            conn.updateServer(server)
            conn.markConnecting()
            // A re-issued request supersedes any prior poll for this id; cancel it
            // so two polls can't race to openSession on the same satellite.
            cancelApprovalPoll(id)
            val job =
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
                    // Poll until accept / deny / timeout. pairWithPin shares this
                    // connection: once it reaches Live, bail — the poll's terminal
                    // paths must not tear down a session the PIN just established.
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
            approvalPollJobs[id] = job
            // Self-remove so a completed/cancelled poll doesn't linger in the map;
            // guarded so we never evict a newer poll that already replaced this id.
            job.invokeOnCompletion { approvalPollJobs.remove(id, job) }
        }

        // Cancels and deregisters an in-flight Path-B approval poll for [id], if any.
        private fun cancelApprovalPoll(id: String) {
            approvalPollJobs.remove(id)?.cancel()
        }

        // Pairing key + proof for an authenticated REST call; null when the key
        // is absent or undecodable (both mean: re-pair).
        private class Credentials(
            val pairingKey: ByteArray,
            val proof: String,
        )

        private fun credentialsFor(id: String): Credentials? {
            val keyHex = store.satelliteSharedKey(id) ?: return null
            val key = if (keyHex.length == 64) runCatching { hexToBytes(keyHex) }.getOrNull() else null
            if (key == null || key.size != 32) return null
            return Credentials(key, SessionCrypto.hmacProof(key, deviceId))
        }

        /**
         * The declarative connect: ONE `PUT /api/connections` carries identity,
         * proof of the pairing key, and the FULL controller topology. The
         * response is the applied state; partial controller failures ride in it
         * and surface without aborting the session.
         */
        private suspend fun openSession(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
        ) = withContext(ioDispatcher) {
            val id = SatelliteConnection.idFor(server)
            // Vet the discovered address before any socket: an unauthenticated
            // mDNS/broadcast beacon must not steer us at a public or non-literal
            // host. Every connect path funnels through here, so both discovery
            // transports are covered at one choke point.
            if (!isPrivateHostLiteral(server.ip)) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                return@withContext
            }
            val creds = credentialsFor(id)
            if (creds == null) {
                conn.markDisconnected()
                store.forgetSatelliteSharedKey(id)
                markStale(id)
                emitErrorIfUserInitiated(intent, REPAIR_NEEDED_MSG)
                return@withContext
            }
            val descriptors = conn.desiredDescriptors()
            val rawResp =
                runCatching {
                    discoveryRepo.putSession(
                        server.ip,
                        server.httpPort,
                        deviceId,
                        deviceName,
                        creds.proof,
                        ControllerDescriptor.arrayJson(descriptors),
                        conn.wantsMouseControl(),
                    )
                }.getOrNull()
            if (rawResp.isNullOrBlank()) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                scheduleRetry(conn, server, intent)
                return@withContext
            }
            val resp =
                runCatching { json.decodeFromString(SessionResponse.serializer(), rawResp) }
                    .getOrNull()
            if (resp == null) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, SERVER_UNREACHABLE_MSG)
                scheduleRetry(conn, server, intent)
                return@withContext
            }
            if (resp.unauthorized) {
                // Terminal by contract: the server no longer trusts our key (or
                // never did). Stop retrying — only a re-pair can fix this.
                conn.markDisconnected()
                store.forgetSatelliteSharedKey(id)
                markStale(id)
                emitErrorIfUserInitiated(intent, REPAIR_NEEDED_MSG)
                return@withContext
            }
            val connId = resp.connectionId
            val tokenHex = resp.token
            val saltHex = resp.sessionSalt
            if (connId == null || tokenHex == null || saltHex == null) {
                conn.markDisconnected()
                emitErrorIfUserInitiated(intent, "Error: ${resp.error ?: "connection failed"}")
                scheduleRetry(conn, server, intent)
                return@withContext
            }
            // Token + salt are server-supplied: malformed values must degrade
            // like a refused connect, not crash the coroutine.
            val token = runCatching { hexToBytes(tokenHex) }.getOrNull()
            val salt = runCatching { hexToBytes(saltHex) }.getOrNull()
            if (token == null || token.size != 4 || salt == null || salt.size != 8) {
                conn.markDisconnected()
                return@withContext
            }
            // Per-session key: the pairing key never touches the UDP path.
            val sessionKey = SessionCrypto.deriveSessionKey(creds.pairingKey, salt, token)
            val handle = controllerRepo.openSocket(server.ip, server.udpPort)
            if (handle < 0) {
                conn.markDisconnected()
                return@withContext
            }
            controllerRepo.setConnectionParams(handle, token, sessionKey)
            store.rememberSatellite(server)
            // Successful session: any Stale marker we set on the way here no
            // longer applies, and the chip flips Live → Online.
            clearStale(id)
            retryAttempts.remove(id)
            conn.markConnected(
                handle,
                connId,
                resp.epoch,
                resp.controllers,
                mouseControlGranted = resp.hostFeatures.mouseControl.granted,
                onDead = {
                    // Silent auto-retry with exponential backoff — the row chip
                    // handles the user-visible narrative.
                    disconnect(conn.id)
                    scheduleRetry(conn, server, ConnectIntent.RETRY_AFTER_DEATH)
                },
                onClosedByServer = { reason -> handleServerClose(conn, server, reason) },
                onReconcileNeeded = { scope.launch(ioDispatcher) { reconcile(conn, server) } },
                onApplyFailures = { failures ->
                    scope.launch {
                        _events.emit(
                            ConnectionEvent.Error(
                                "Couldn't apply controller on ${server.name} — " +
                                    failures.joinToString { "#${it.ctrlIdx}: ${it.result}" },
                            ),
                        )
                    }
                },
            )
        }

        /**
         * Authenticated close-notify (0x000F): the session is gone server-side
         * RIGHT NOW — no death-timeout wait. The reason picks the follow-up:
         * unpaired is terminal (re-pair needed); a rotation-superseded session
         * stays down (its replacement is already live); shutdown/kick retry on
         * the backoff curve (the kick is transient by design).
         */
        private fun handleServerClose(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            reason: Int,
        ) {
            val id = conn.id
            disconnect(id)
            when (reason) {
                SatelliteConnection.CLOSE_REASON_UNPAIRED -> {
                    store.forgetSatelliteSharedKey(id)
                    markStale(id)
                }
                SatelliteConnection.CLOSE_REASON_REPLACED -> Unit
                else -> scheduleRetry(conn, server, ConnectIntent.RETRY_AFTER_DEATH)
            }
        }

        // Bounded exponential backoff for the silent retry paths; a user tap
        // resets the curve. Never schedules for USER_INITIATED — the user gets
        // immediate feedback instead of a background loop they didn't ask for.
        private fun scheduleRetry(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
        ) {
            if (intent == ConnectIntent.USER_INITIATED) return
            val id = conn.id
            val attempt = retryAttempts.merge(id, 1, Int::plus) ?: 1
            val delayMs =
                (RETRY_BASE_MS shl (attempt - 1).coerceAtMost(RETRY_MAX_SHIFT))
                    .coerceAtMost(RETRY_MAX_MS)
            scope.launch {
                kotlinx.coroutines.delay(delayMs)
                if (_connections.value[id]?.state?.value == SatelliteSessionState.Idle &&
                    id !in _staleSatelliteIds.value
                ) {
                    connect(server, ConnectIntent.RETRY_AFTER_DEATH)
                }
            }
        }

        /**
         * Heartbeat acks said the server's topology no longer matches ours
         * (epoch/bitmap drift). GET the applied state; if it actually matches
         * the desired set, just adopt the epoch (a benign drift, e.g. our own
         * standalone PUT raced an ack). Otherwise re-PUT the full desired state
         * — the declarative converge makes the retry free.
         */
        @Suppress("ReturnCount") // converge guard-chain: every early return is a distinct no-op case
        private suspend fun reconcile(
            conn: SatelliteConnection,
            server: DiscoveredServer,
        ) {
            val id = conn.id
            if (reconcileInFlight.putIfAbsent(id, true) != null) return
            try {
                val connId = conn.connectionId ?: return
                val creds = credentialsFor(id) ?: return
                val raw =
                    runCatching {
                        discoveryRepo.getSession(server.ip, server.httpPort, connId, deviceId, creds.proof)
                    }.getOrNull() ?: return
                val view =
                    runCatching { json.decodeFromString(SessionViewDto.serializer(), raw) }
                        .getOrNull() ?: return
                if (view.code == SessionResponse.CODE_NOT_PAIRED || view.code == SessionResponse.CODE_BAD_PROOF) {
                    conn.markDisconnected()
                    store.forgetSatelliteSharedKey(id)
                    markStale(id)
                    return
                }
                if (view.connectionId == connId && conn.matchesAppliedView(view)) {
                    conn.adoptEpoch(view.epoch)
                    return
                }
                // Applied ≠ desired (or the session is gone): converge with a
                // fresh session PUT. Tear the UDP tuple down first — the PUT
                // rotates token/key.
                conn.markDisconnected()
                conn.markConnecting()
                openSession(conn, server, ConnectIntent.RETRY_AFTER_DEATH)
            } finally {
                reconcileInFlight.remove(id)
            }
        }

        // Single-slot converge while the session is live (PUT .../controllers/{idx}).
        // The session — and its UDP keys — never churn for a toggle.
        @Suppress("ReturnCount") // converge guard-chain: every early return is a distinct no-op case
        private suspend fun syncSlot(
            id: String,
            slotId: String,
        ) {
            val conn = _connections.value[id] ?: return
            if (conn.state.value != SatelliteSessionState.Live) return
            val connId = conn.connectionId ?: return
            val server = conn.server.value
            val creds = credentialsFor(id) ?: return
            val descriptor = conn.descriptorFor(slotId) ?: return
            val raw =
                runCatching {
                    discoveryRepo.putController(
                        server.ip,
                        server.httpPort,
                        connId,
                        descriptor.ctrlIdx,
                        deviceId,
                        creds.proof,
                        descriptor.toJson(),
                    )
                }.getOrNull() ?: return
            val resp =
                runCatching { json.decodeFromString(ControllerPutResponse.serializer(), raw) }
                    .getOrNull() ?: return
            if (resp.code == SessionResponse.CODE_NOT_PAIRED || resp.code == SessionResponse.CODE_BAD_PROOF) {
                conn.markDisconnected()
                store.forgetSatelliteSharedKey(id)
                markStale(id)
                return
            }
            val result = resp.controller
            if (result == null) {
                // 404 connection-not-found: the session died under us; the
                // alive-poll/close-notify path owns recovery. Nothing to fold in.
                return
            }
            conn.adoptEpoch(resp.epoch)
            conn.applyResults(listOf(result), onApplyFailures = { failures ->
                scope.launch {
                    _events.emit(
                        ConnectionEvent.Error(
                            "Couldn't apply controller on ${server.name} — " +
                                failures.joinToString { "#${it.ctrlIdx}: ${it.result}" },
                        ),
                    )
                }
            })
            if (conn.wantsMouseControl() != conn.mouseControlGranted) {
                // The toggle changed the session-level desire, but the grant is
                // only computed at session PUT (contract §hostFeatures) —
                // converge the full session so the request rides along.
                reconcile(conn, server)
            }
        }

        // Slot delete while live (DELETE .../controllers/{idx}) — removes the
        // SLOT only; the session lives on (zero-controller sessions are valid).
        private suspend fun deleteSlot(
            id: String,
            ctrlIdx: Int,
        ) {
            val conn = _connections.value[id] ?: return
            val connId = conn.connectionId ?: return
            val server = conn.server.value
            val creds = credentialsFor(id) ?: return
            val raw =
                runCatching {
                    discoveryRepo.deleteController(
                        server.ip,
                        server.httpPort,
                        connId,
                        ctrlIdx,
                        deviceId,
                        creds.proof,
                    )
                }.getOrNull() ?: return
            val resp =
                runCatching { json.decodeFromString(ControllerPutResponse.serializer(), raw) }
                    .getOrNull() ?: return
            if (resp.error == null) conn.adoptEpoch(resp.epoch)
        }

        // Errors surface only for "I asked for this"; on the silent intents the
        // row chip carries the feedback and a banner would be unsolicited noise.
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

        fun disconnect(id: String) {
            // Stop any reverse-pairing poll first: otherwise it could call
            // openSession seconds after the user tore the connection down.
            cancelApprovalPoll(id)
            val conn = _connections.value[id] ?: return
            val srv = conn.server.value
            val cid = conn.connectionId
            conn.markDisconnected()
            if (cid != null) {
                val proof = credentialsFor(id)?.proof
                if (proof != null) {
                    scope.launch(ioDispatcher) {
                        runCatching { discoveryRepo.disconnect(srv.ip, srv.httpPort, cid, deviceId, proof) }
                    }
                }
            }
        }

        fun forget(id: String) {
            // Self-unpair BEFORE dropping the key (the proof needs it): the
            // satellite closes any live session and drops its trust row, so a
            // forgotten dish can't keep a paired ghost server-side.
            val conn = _connections.value[id]
            val proof = credentialsFor(id)?.proof
            if (conn != null && proof != null) {
                val srv = conn.server.value
                scope.launch(ioDispatcher) {
                    runCatching { discoveryRepo.unpair(srv.ip, srv.httpPort, deviceId, proof) }
                }
            }
            disconnect(id)
            store.forgetSatellite(id)
            clearStale(id)
            retryAttempts.remove(id)
            _connections.update { it - id }
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

        companion object {
            private const val DISC_PORT = 9879
            private const val DISC_TIMEOUT_MS = 4000

            // Bounded exponential backoff for silent reconnects: 1s, 2s, 4s …
            // capped at 60s. A momentary Wi-Fi drop self-heals fast; a real
            // outage stops hammering the satellite's buffers.
            private const val RETRY_BASE_MS = 1000L
            private const val RETRY_MAX_MS = 60_000L
            private const val RETRY_MAX_SHIFT = 6

            internal const val SERVER_UNREACHABLE_MSG =
                "Server unreachable — check it's powered on and on the same Wi-Fi."

            internal const val REPAIR_NEEDED_MSG =
                "This satellite no longer recognizes this device — re-pair needed."

            private const val APPROVAL_POLL_INTERVAL_MS = 2000L

            // Matches the satellite's 2-minute pairing-request TTL. Stop waiting
            // once the request can no longer be accepted on the other side.
            private const val APPROVAL_TIMEOUT_MS = 120_000L

            internal const val APPROVAL_DECLINED_MSG =
                "The satellite declined the pairing request."
            internal const val APPROVAL_TIMEOUT_MSG =
                "No response from the satellite. The pairing request timed out."
        }
    }
