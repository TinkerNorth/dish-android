// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.content.Context
import androidx.core.content.edit
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.ControllerApplyDto
import com.tinkernorth.dish.core.model.ControllerPutResponse
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.PairResponse
import com.tinkernorth.dish.core.model.SessionResponse
import com.tinkernorth.dish.core.model.SessionViewDto
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.core.net.DISH_PROTOCOL_CURRENT
import com.tinkernorth.dish.core.net.DISH_PROTOCOL_MIN
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.core.net.PAIRING_KEY_BYTES
import com.tinkernorth.dish.core.net.PAIRING_KEY_HEX_LEN
import com.tinkernorth.dish.core.net.SESSION_SALT_BYTES
import com.tinkernorth.dish.core.net.TOKEN_BYTES
import com.tinkernorth.dish.core.net.controllersArrayJson
import com.tinkernorth.dish.core.net.deriveSessionKey
import com.tinkernorth.dish.core.net.dishProtocolSpeakFor
import com.tinkernorth.dish.core.net.hexToBytes
import com.tinkernorth.dish.core.net.hmacProof
import com.tinkernorth.dish.core.net.isPrivateHostLiteral
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.system.isGranted
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val EVENT_BUFFER = 8

// USER_INITIATED surfaces failures; the silent intents rely on the row chip for feedback.
enum class ConnectIntent { USER_INITIATED, AUTO_RECONNECT, RETRY_AFTER_DEATH }

sealed class ConnectionEvent {
    data class PairingRequired(
        val server: DiscoveredServer,
    ) : ConnectionEvent()

    data class Error(
        val error: ConnectionError,
    ) : ConnectionEvent()
}

internal data class LateSlotConverge(
    val resyncs: List<Int>,
    val deletes: List<Int>,
)

internal fun lateSlotConverge(
    sent: List<ControllerDescriptor>,
    desired: List<ControllerDescriptor>,
): LateSlotConverge {
    val sentByIdx = sent.associateBy { it.ctrlIdx }
    val desiredIdx = desired.mapTo(mutableSetOf()) { it.ctrlIdx }
    return LateSlotConverge(
        resyncs = desired.filter { sentByIdx[it.ctrlIdx] != it }.map { it.ctrlIdx },
        deletes = sent.map { it.ctrlIdx }.filter { it !in desiredIdx },
    )
}

// A round trip that failed reads as no reply. A caller cancelled meanwhile stops here instead:
// runCatching alone would hand it a null to act on as though the satellite had not answered.
private suspend inline fun <T> replyOrNull(call: () -> T): T? {
    val reply = runCatching(call).getOrNull()
    currentCoroutineContext().ensureActive()
    return reply
}

// Token + salt are server-supplied: malformed values must degrade like a
// refused connect, not crash the coroutine. Per-session key derivation
// keeps the pairing key off the UDP path; null means no wire came up.
private fun ControllerRepository.openWire(
    server: DiscoveredServer,
    pairingKey: ByteArray,
    tokenHex: String,
    saltHex: String,
    negotiated: Int,
): Int? {
    val token = runCatching { hexToBytes(tokenHex) }.getOrNull()
    val salt = runCatching { hexToBytes(saltHex) }.getOrNull()
    val tokenIsWhole = token != null && token.size == TOKEN_BYTES
    val saltIsWhole = salt != null && salt.size == SESSION_SALT_BYTES
    if (!tokenIsWhole || !saltIsWhole) return null
    val sessionKey = deriveSessionKey(pairingKey, salt, token)
    val handle = openSocket(server.ip, server.udpPort)
    if (handle < 0) return null
    setConnectionParams(handle, token, sessionKey, negotiated)
    return handle
}

private fun rejectsOurCredentials(code: String?): Boolean =
    code == SessionResponse.CODE_NOT_PAIRED || code == SessionResponse.CODE_BAD_PROOF

/**
 * The session a live connection's callbacks and converge steps speak for: the generation it was
 * adopted under and the connection id the satellite granted it. The generation alone does not name
 * a session: one ended without a disconnect (torn down for a fresh PUT, or dropped on a rejected
 * slot put) leaves it where it was, and the next session is adopted under the same one.
 */
private class SessionTicket(
    val generation: Int,
    val connectionId: String,
)

// Tears a live session down for a fresh session PUT (reconcile, rekey): the PUT rotates token and key.
private fun SatelliteConnection.restartForFreshPut() {
    markDisconnected()
    markConnecting()
}

// A rekey restarts only a Live session; false when the session is in any other state.
private fun SatelliteConnection.restartForFreshPutIfLive(): Boolean {
    val isLive = state.value == SatelliteSessionState.Live
    if (isLive) restartForFreshPut()
    return isLive
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
        private val capabilityProvider: Provider<CapabilityComposer>,
        private val hostFacts: SatelliteHostFacts,
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
                extraBufferCapacity = EVENT_BUFFER,
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

        // Consecutive silent-retry count per satellite id: drives the
        // exponential backoff. Reset on a successful session or any user action.
        private val retryAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        // Silent retries still waiting out their backoff, per satellite id: disconnect cancels
        // them all, since one that fired afterwards would undo the disconnect.
        private val pendingRetries = ConcurrentHashMap<String, MutableSet<Job>>()

        // Bumped by every disconnect. A handshake notes the generation it started under; when a
        // disconnect has moved it on by the time a round trip answers, the handshake stops without
        // touching the connection (a newer handshake may own it by then) and hands back any session
        // it was granted. Written only under transitionLock.
        private val disconnectGenerations = ConcurrentHashMap<String, Int>()

        // A disconnect (from the UI thread) and a handshake, converge or retry (on the app scope's
        // workers) each change the connection under this lock together with the generation check that
        // decides the change, so a disconnect lands wholly before or wholly after it, never between.
        private val transitionLock = Any()

        // Satellites the user disconnected: every reconnect the app makes on its own (the foreground
        // auto reconnect, a reappearing host, a silent retry) leaves them down until the user connects
        // one again or forgets it. A teardown the manager makes itself marks nothing. In memory only,
        // for this process. Added and checked under transitionLock, so a user disconnect lands wholly
        // before or after a silent connect's check.
        private val userDisconnected: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // Single-flight reconcile guard per id: heartbeat ticks fire every
        // second, the reconcile round-trip can take longer.
        private val reconcileInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        // The protocol version settled with each satellite (offer accepted, or the 409
        // echo). Cleared on forget so a replaced install renegotiates from scratch.
        private val negotiatedProtocol = java.util.concurrent.ConcurrentHashMap<String, Int>()

        init {
            // The composer's wire projection moves only when a descriptor would (caps bits or
            // touchpad mode), so every emission is a reason to converge EVERY bound slot.
            scope.launch {
                capabilityProvider.get().wireProjection.collect {
                    _connections.value.values.forEach { conn ->
                        conn.refreshCapsIfChanged()
                    }
                }
            }
        }

        fun get(id: String): SatelliteConnection? = _connections.value[id]

        // The version to offer [id] on the next request, or null when the satellite is
        // verifiably older than anything this client still speaks. Seeded from the
        // advertisement documents; settled by the 409 echo; remembered per satellite.
        private fun versionToSpeak(id: String): Int? {
            negotiatedProtocol[id]?.let { return it }
            val advertised =
                hostFacts.features
                    .featuresFor(id)
                    ?.protocolVersion
                    ?.takeIf { it > 0 }
            return dishProtocolSpeakFor(advertised)
        }

        private fun noteNegotiated(
            id: String,
            version: Int,
        ) {
            negotiatedProtocol[id] = version
            hostFacts.features.noteProtocolVersion(id, version)
        }

        // A 409's `supported` echo we can also speak means one retry settles it; null
        // means no shared version exists and protocolRejectMessage says which side to update.
        private fun protocolRetryVersion(body: String): Int? {
            val supported = supportedVersionFrom(body) ?: return null
            return supported.takeIf { it in DISH_PROTOCOL_MIN..DISH_PROTOCOL_CURRENT }
        }

        private fun protocolRejectMessage(body: String): ConnectionError =
            when {
                (supportedVersionFrom(body) ?: 0) > DISH_PROTOCOL_CURRENT -> ConnectionError.AppUpdateRequired
                else -> ConnectionError.SatelliteUpdateRequired
            }

        private fun supportedVersionFrom(body: String): Int? =
            runCatching { json.decodeFromString(SessionResponse.serializer(), body) }
                .getOrNull()
                ?.supported
                ?.takeIf { it > 0 }

        private class NegotiatedPut(
            val reply: HttpReply?,
            val speak: Int,
        )

        // One session PUT at the version we'd speak, retried once when the 409 echoes
        // a version this client also speaks. Null when no shared version exists at
        // all; a surviving 409 maps through protocolRejectMessage in the caller.
        private suspend fun putSessionNegotiated(
            id: String,
            conn: SatelliteConnection,
            server: DiscoveredServer,
            proof: String,
            descriptors: List<ControllerDescriptor>,
        ): NegotiatedPut? {
            val offered = versionToSpeak(id) ?: return null
            val firstReply = putSessionAt(conn, server, proof, descriptors, offered)
            val retryWith = firstReply?.takeIf { it.status == HTTP_CONFLICT }?.let { protocolRetryVersion(it.body) }
            if (retryWith == null) return NegotiatedPut(firstReply, offered)
            noteNegotiated(id, retryWith)
            val retryReply = putSessionAt(conn, server, proof, descriptors, retryWith)
            return NegotiatedPut(retryReply, retryWith)
        }

        private suspend fun putSessionAt(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            proof: String,
            descriptors: List<ControllerDescriptor>,
            version: Int,
        ): HttpReply? =
            replyOrNull {
                discoveryRepo.putSession(
                    server.ip,
                    server.httpPort,
                    deviceId,
                    deviceName,
                    proof,
                    controllersArrayJson(descriptors),
                    conn.wantsMouseControl(),
                    version,
                )
            }

        // One pair round-trip at the version we'd speak, retried once when the 409
        // echoes a version this client also speaks. Callers keep their reply-shape
        // handling; a surviving 409 maps through protocolRejectMessage.
        private suspend fun pairNegotiated(
            id: String,
            server: DiscoveredServer,
            pin: String,
            clientPin: String = "",
        ): HttpReply? {
            val speak = versionToSpeak(id) ?: DISH_PROTOCOL_MIN
            val first =
                replyOrNull {
                    discoveryRepo.pair(
                        server.ip,
                        server.pairPort,
                        deviceId,
                        deviceName,
                        pin,
                        clientPin,
                        protocolVersion = speak,
                    )
                }
            if (first == null || first.status != HTTP_CONFLICT) return first
            val retryWith = protocolRetryVersion(first.body) ?: return first
            noteNegotiated(id, retryWith)
            return replyOrNull {
                discoveryRepo.pair(
                    server.ip,
                    server.pairPort,
                    deviceId,
                    deviceName,
                    pin,
                    clientPin,
                    protocolVersion = retryWith,
                )
            }
        }

        private fun generationOf(id: String): Int = disconnectGenerations[id] ?: 0

        private fun isSuperseded(
            id: String,
            generation: Int,
        ): Boolean = generationOf(id) != generation

        // Runs [transition] only while no disconnect has overtaken [generation]; null when one has.
        private inline fun <T> ifCurrent(
            id: String,
            generation: Int,
            transition: () -> T,
        ): T? =
            synchronized(transitionLock) {
                if (isSuperseded(id, generation)) null else transition()
            }

        // Runs [transition] only while [session] is still the connection's: no disconnect overtook it and
        // no later session replaced it. Null when either has.
        private inline fun <T> ifSessionCurrent(
            conn: SatelliteConnection,
            session: SessionTicket,
            transition: () -> T,
        ): T? =
            synchronized(transitionLock) {
                val isTheSameSession = conn.connectionId == session.connectionId
                if (isSuperseded(conn.id, session.generation) || !isTheSameSession) null else transition()
            }

        // The generation is read before the connection turns Linking: a disconnect that lands as a
        // handshake starts must supersede it, not hand it the generation that disconnect began.
        private fun beginHandshake(
            conn: SatelliteConnection,
            server: DiscoveredServer,
        ): Int =
            synchronized(transitionLock) {
                val generation = generationOf(conn.id)
                conn.updateServer(server)
                conn.markConnecting()
                generation
            }

        // Idempotent on live/in-flight: a foreground kick must not restart pair/auth mid-handshake, so a
        // connection that is not Idle only takes the fresh address, and null says no handshake began.
        // The Idle check and the turn to Linking are one locked step: of two connects landing together,
        // exactly one finds the connection Idle. A connect the user did not ask for begins nothing on
        // a satellite the user disconnected.
        private fun beginHandshakeIfIdle(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
        ): Int? =
            synchronized(transitionLock) {
                val isHeldDownByTheUser = intent != ConnectIntent.USER_INITIATED && conn.id in userDisconnected
                if (isHeldDownByTheUser) return null
                if (conn.state.value != SatelliteSessionState.Idle) {
                    conn.updateServer(server)
                    return null
                }
                beginHandshake(conn, server)
            }

        // False when a disconnect overtook the handshake: a newer one may own the connection by then.
        private fun markDisconnectedIfCurrent(
            conn: SatelliteConnection,
            generation: Int,
        ): Boolean = ifCurrent(conn.id, generation) { conn.markDisconnected() } != null

        // The grant becomes the connection's only while this handshake still owns it: no disconnect
        // overtook it, and it is still Linking (a sibling handshake under the same generation, a PIN
        // submitted while a connect was linking, may have gone Live first).
        private fun adoptGrant(
            conn: SatelliteConnection,
            generation: Int,
            grant: SatelliteConnection.SessionGrant,
            facts: SatelliteConnection.SessionFacts,
            callbacks: SatelliteConnection.SessionCallbacks,
        ): Boolean =
            ifCurrent(conn.id, generation) {
                val ownsTheConnection = conn.state.value == SatelliteSessionState.Linking
                if (ownsTheConnection) {
                    conn.protocolVersion = facts.protocolVersion
                    conn.noteSessionFacts(facts.maxControllers, facts.protocolVersion)
                }
                ownsTheConnection && conn.markConnected(grant, callbacks)
            } == true

        fun remembered(): List<RememberedSatellite> = store.remembered()

        fun startDiscovery() {
            if (!_isScanning.compareAndSet(expect = false, update = true)) return
            scope.launch {
                val servers = discoveryRepo.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
                store.refreshFromDiscovery(servers)
                servers.forEach { server ->
                    val conn = _connections.value[satelliteConnectionIdFor(server)]
                    if (conn != null && conn.state.value == SatelliteSessionState.Idle) {
                        conn.updateServer(server)
                    }
                }
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
                hooks =
                    SatelliteConnection.Hooks(
                        wireCapsFor = { slotId -> capabilityProvider.get().wireCapsFor(slotId) },
                        touchpadModeFor = { slotId -> capabilityProvider.get().touchpadWireMode(slotId) },
                        onSlotChanged = { slotId -> scope.launch(ioDispatcher) { syncSlot(id, slotId) } },
                        onSlotRemoved = { ctrlIdx -> scope.launch(ioDispatcher) { deleteSlot(id, ctrlIdx) } },
                    ),
                motionBackendStatusStore = hostFacts.motionBackend,
            )

        private fun findOrCreate(
            id: String,
            server: DiscoveredServer,
        ): SatelliteConnection =
            _connections
                .updateAndGet { map ->
                    if (map.containsKey(id)) return@updateAndGet map
                    map + (id to newConnection(id, server))
                }.getValue(id)

        fun connect(
            server: DiscoveredServer,
            intent: ConnectIntent = ConnectIntent.USER_INITIATED,
        ) {
            // Only user-initiated connects (which prompt) may open LAN sockets before the Android 17 grant.
            if (intent != ConnectIntent.USER_INITIATED && !isGranted(context)) return
            val id = satelliteConnectionIdFor(server)
            if (intent == ConnectIntent.USER_INITIATED) {
                retryAttempts.remove(id)
                userDisconnected.remove(id)
            }
            // Atomic find-or-create: prevents two concurrent first-time connects allocating duplicates.
            val conn = findOrCreate(id, server)
            val generation = beginHandshakeIfIdle(conn, server, intent) ?: return
            scope.launch {
                if (store.satelliteSharedKey(id) != null) {
                    openSession(conn, server, intent, generation)
                } else {
                    pairAndConnect(conn, server, intent, generation)
                }
            }
        }

        private suspend fun pairAndConnect(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
            generation: Int,
        ) {
            val id = satelliteConnectionIdFor(server)
            val reply = pairNegotiated(id, server, pin = "")
            if (reply == null || reply.unreachable) {
                return failSession(conn, server, intent, unreachableMessage(reply), retry = false, generation)
            }
            if (reply.status == HTTP_CONFLICT) {
                return failSession(conn, server, intent, protocolRejectMessage(reply.body), retry = false, generation)
            }
            val pair =
                runCatching { json.decodeFromString(PairResponse.serializer(), reply.body) }
                    .getOrNull()
            if (pair == null) {
                return failSession(conn, server, intent, ConnectionError.ServerUnreachable, retry = false, generation)
            }
            if (!pair.ok || pair.sharedKey == null) {
                // Reachable but no key and no PIN sent: first-time pair / server forgot us.
                if (markDisconnectedIfCurrent(conn, generation)) {
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
                }
                return
            }
            if (isSuperseded(id, generation)) return
            clearStale(id)
            store.setSatelliteSharedKey(id, pair.sharedKey)
            openSession(conn, server, intent, generation)
        }

        fun pairWithPin(
            server: DiscoveredServer,
            pin: String,
        ) {
            val id = satelliteConnectionIdFor(server)
            retryAttempts.remove(id)
            userDisconnected.remove(id)
            // Atomic find-or-create (as in connect): concurrent PIN submits must not
            // allocate duplicates, and a submit must not stack on a live session.
            val conn = findOrCreate(id, server)
            if (conn.state.value == SatelliteSessionState.Live) return
            val generation = beginHandshake(conn, server)
            scope.launch {
                val reply = pairNegotiated(id, server, pin)
                if (reply == null || reply.unreachable) {
                    return@launch failUserHandshake(conn, server, unreachableMessage(reply), generation)
                }
                if (reply.status == HTTP_CONFLICT) {
                    return@launch failUserHandshake(conn, server, protocolRejectMessage(reply.body), generation)
                }
                val pair =
                    runCatching { json.decodeFromString(PairResponse.serializer(), reply.body) }
                        .getOrNull()
                if (pair == null) {
                    return@launch failUserHandshake(conn, server, ConnectionError.ServerUnreachable, generation)
                }
                if (!pair.ok || pair.sharedKey == null) {
                    return@launch failUserHandshake(conn, server, pairingRefusal(pair.error), generation)
                }
                if (isSuperseded(id, generation)) return@launch
                clearStale(id)
                store.setSatelliteSharedKey(id, pair.sharedKey)
                openSession(conn, server, ConnectIntent.USER_INITIATED, generation)
            }
        }

        /**
         * Path B: the dish shows [clientPin]; the operator accepts it on the
         * satellite. Submit, then poll /api/pair/status until accept (→ open
         * the session), decline, or timeout: the pairing key only arrives via
         * the poll. An already-paired device never lands here: connect()
         * routes a keyed satellite straight to the session PUT.
         */
        fun requestApproval(
            server: DiscoveredServer,
            clientPin: String,
        ) {
            val id = satelliteConnectionIdFor(server)
            retryAttempts.remove(id)
            userDisconnected.remove(id)
            val conn = findOrCreate(id, server)
            // A re-issued request supersedes any prior poll for this id, so two polls can't race to
            // openSession on the same satellite. The new poll is registered in the same locked step
            // that starts its handshake and runs only after it, so a disconnect or a re-issue from
            // then on reaches it by cancellation, and its ending steps can tell it is no longer current.
            val poll =
                synchronized(transitionLock) {
                    approvalPollJobs.remove(id)?.cancel()
                    val generation = beginHandshake(conn, server)
                    val registered =
                        scope.launch(start = CoroutineStart.LAZY) {
                            awaitApproval(conn, server, clientPin, generation)
                        }
                    approvalPollJobs[id] = registered
                    registered
                }
            // Self-remove so a completed/cancelled poll doesn't linger in the map;
            // guarded so we never evict a newer poll that already replaced this id.
            poll.invokeOnCompletion { approvalPollJobs.remove(id, poll) }
            poll.start()
        }

        private suspend fun awaitApproval(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            clientPin: String,
            generation: Int,
        ) {
            val id = conn.id
            val reply = pairNegotiated(id, server, pin = "", clientPin = clientPin)
            val pairFailure =
                when {
                    reply == null || reply.unreachable -> unreachableMessage(reply)
                    reply.status == HTTP_CONFLICT -> protocolRejectMessage(reply.body)
                    else -> null
                }
            if (pairFailure != null) return failApprovalRequest(conn, pairFailure, generation)
            // A disconnect that landed while the handshake turned Linking, before this poll was
            // registered, reached it through the generation alone: no cancellation did.
            if (isSuperseded(id, generation)) return
            // Poll until accept / deny / timeout. pairWithPin shares this
            // connection: once it reaches Live, bail. The poll's terminal
            // paths must not tear down a session the PIN just established.
            var waited = 0L
            while (waited < APPROVAL_TIMEOUT_MS && conn.state.value != SatelliteSessionState.Live) {
                kotlinx.coroutines.delay(APPROVAL_POLL_INTERVAL_MS)
                waited += APPROVAL_POLL_INTERVAL_MS
                val statusRaw =
                    replyOrNull { discoveryRepo.pairStatus(server.ip, server.httpPort, deviceId) }
                        ?.takeIf { !it.unreachable }
                        ?.body
                // A transient null reply is treated as still-pending, not a refusal.
                val st =
                    if (statusRaw.isNullOrBlank()) {
                        Status.Pending
                    } else {
                        classifyStatus(statusRaw)
                    }
                // Re-check: Live may have flipped during the poll round-trip.
                if (conn.state.value == SatelliteSessionState.Live) return
                if (st is Status.Approved) {
                    clearStale(id)
                    store.setSatelliteSharedKey(id, st.sharedKeyHex)
                    // Out of the poll job, as connect() runs it: a disconnect cancels the
                    // poll, and a cancel landing on the session PUT would cut it off from
                    // the session it was granted. The generation stops it instead, and
                    // hands that session back.
                    scope.launch { openSession(conn, server, ConnectIntent.USER_INITIATED, generation) }
                    return
                }
                if (st is Status.Declined) {
                    return failApprovalRequest(conn, ConnectionError.ApprovalDeclined, generation)
                }
            }
            if (conn.state.value != SatelliteSessionState.Live) {
                failApprovalRequest(conn, ConnectionError.ApprovalTimedOut, generation)
            }
        }

        // The request ends only while its poll is still the registered one and no disconnect overtook
        // it: a request re-issued just as this one's ending answer arrived is left Linking.
        private suspend fun failApprovalRequest(
            conn: SatelliteConnection,
            error: ConnectionError,
            generation: Int,
        ) {
            val poll = currentCoroutineContext().job
            val ended =
                synchronized(transitionLock) {
                    val isTheCurrentRequest = approvalPollJobs[conn.id] === poll
                    isTheCurrentRequest && markDisconnectedIfCurrent(conn, generation)
                }
            if (ended) emitErrorIfUserInitiated(ConnectIntent.USER_INITIATED, error)
        }

        // Pairing key + proof for an authenticated REST call; null when the key
        // is absent or undecodable (both mean: re-pair).
        private class Credentials(
            val pairingKey: ByteArray,
            val proof: String,
        )

        private fun credentialsFor(id: String): Credentials? {
            val keyHex = store.satelliteSharedKey(id) ?: return null
            val key = if (keyHex.length == PAIRING_KEY_HEX_LEN) runCatching { hexToBytes(keyHex) }.getOrNull() else null
            if (key == null || key.size != PAIRING_KEY_BYTES) return null
            return Credentials(key, hmacProof(key, deviceId))
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
            generation: Int,
        ) = withContext(ioDispatcher) {
            val id = satelliteConnectionIdFor(server)
            // Vet the discovered address before any socket: an unauthenticated
            // mDNS/broadcast beacon must not steer us at a public or non-literal
            // host. Every connect path funnels through here, so both discovery
            // transports are covered at one choke point.
            if (!isPrivateHostLiteral(server.ip)) {
                return@withContext failSession(conn, server, intent, ConnectionError.ServerUnreachable, retry = false, generation)
            }
            val creds = credentialsFor(id) ?: return@withContext dropUntrustedKey(conn, intent, generation)
            val descriptors = conn.desiredDescriptors()
            val put = putSessionNegotiated(id, conn, server, creds.proof, descriptors)
            if (isSuperseded(id, generation)) {
                return@withContext releaseStaleGrant(server, put?.reply, creds.proof)
            }
            if (put == null) {
                return@withContext failSession(conn, server, intent, ConnectionError.SatelliteUpdateRequired, retry = false, generation)
            }
            val speak = put.speak
            val reply = put.reply
            if (reply?.status == HTTP_CONFLICT) {
                return@withContext failSession(conn, server, intent, protocolRejectMessage(reply.body), retry = false, generation)
            }
            if (reply?.pinMismatch == true) {
                return@withContext failSession(conn, server, intent, ConnectionError.IdentityChanged, retry = false, generation)
            }
            if (reply == null || reply.unreachable) {
                return@withContext failSession(conn, server, intent, ConnectionError.ServerUnreachable, retry = true, generation)
            }
            if (reply.status == HTTP_CONFLICT) {
                return@withContext failSession(conn, server, intent, protocolRejectMessage(reply.body), retry = false, generation)
            }
            val resp =
                runCatching { json.decodeFromString(SessionResponse.serializer(), reply.body) }
                    .getOrNull()
            if (resp == null) {
                return@withContext failSession(conn, server, intent, ConnectionError.ServerUnreachable, retry = true, generation)
            }
            if (resp.unauthorized) {
                // Terminal by contract: the server no longer trusts our key (or
                // never did). Stop retrying: only a re-pair can fix this.
                return@withContext dropUntrustedKey(conn, intent, generation)
            }
            val connId = resp.connectionId
            val tokenHex = resp.token
            val saltHex = resp.sessionSalt
            if (connId == null || tokenHex == null || saltHex == null) {
                val refusal = sessionRefusal(resp.error)
                return@withContext failSession(conn, server, intent, refusal, retry = true, generation)
            }
            // The response's own version is the settled truth (the satellite accepted the
            // offer, so they match); it keys the wire frames this session encodes.
            val negotiated = resp.protocolVersion.takeIf { it > 0 } ?: speak
            val handle = controllerRepo.openWire(server, creds.pairingKey, tokenHex, saltHex, negotiated)
            if (handle == null) {
                releaseSession(server, connId, creds.proof)
                return@withContext failSession(conn, server, intent, ConnectionError.WireFailed, retry = false, generation)
            }
            noteNegotiated(id, negotiated)
            store.rememberSatellite(server)
            clearStale(id)
            retryAttempts.remove(id)
            val grant =
                SatelliteConnection.SessionGrant(
                    handle = handle,
                    connectionId = connId,
                    epoch = resp.epoch,
                    applied = resp.controllers,
                    mouseControlGranted = resp.hostFeatures.mouseControl.granted,
                )
            val facts = SatelliteConnection.SessionFacts(resp.maxControllers, negotiated)
            val callbacks = sessionCallbacks(conn, server, SessionTicket(generation, connId))
            if (!adoptGrant(conn, generation, grant, facts, callbacks)) {
                // Nobody else will: the connection never held this socket or session.
                controllerRepo.closeSocket(handle)
                return@withContext releaseSession(server, connId, creds.proof)
            }
            convergeSlotChangesSinceSnapshot(id, conn, descriptors)
        }

        // Every callback is judged by the session it speaks for: a heartbeat tick already past its
        // checks when a disconnect (and perhaps a new tap) lands, or when a fresh PUT replaces the
        // session, must not end or converge the session that replaced this one.
        private fun sessionCallbacks(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            session: SessionTicket,
        ) = SatelliteConnection.SessionCallbacks(
            onDead = { onSessionDead(conn, server, session) },
            onClosedByServer = { reason -> handleServerClose(conn, server, reason, session) },
            onReconcileNeeded = { scope.launch(ioDispatcher) { reconcile(conn, server, session) } },
            onRekeyNeeded = { scope.launch(ioDispatcher) { rekey(conn, server, session) } },
            onApplyFailures = { failures -> reportApplyFailures(server.name, failures) },
        )

        // The satellite does not know our key, or ours cannot be read: dropped, and the user told.
        private suspend fun dropUntrustedKey(
            conn: SatelliteConnection,
            intent: ConnectIntent,
            generation: Int,
        ) {
            if (dropRejectedSession(conn, generation)) emitErrorIfUserInitiated(intent, ConnectionError.RepairNeeded)
        }

        // Heartbeat death: the tuple is torn down at once and the silent backoff owns the return.
        private fun onSessionDead(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            session: SessionTicket,
        ) {
            ifSessionCurrent(conn, session) {
                scheduleRetry(conn, server, ConnectIntent.RETRY_AFTER_DEATH, disconnectAt(conn.id))
            }
        }

        private fun reportApplyFailures(
            serverName: String,
            failures: List<ControllerApplyDto>,
        ) {
            val detail = failures.joinToString { "#${it.ctrlIdx}: ${it.result}" }
            scope.launch {
                _events.emit(ConnectionEvent.Error(ConnectionError.ApplyFailed(serverName, detail)))
            }
        }

        private suspend fun convergeSlotChangesSinceSnapshot(
            id: String,
            conn: SatelliteConnection,
            sent: List<ControllerDescriptor>,
        ) {
            if (conn.state.value != SatelliteSessionState.Live) return
            val converge = lateSlotConverge(sent, conn.desiredDescriptors())
            converge.deletes.forEach { ctrlIdx -> deleteSlot(id, ctrlIdx) }
            converge.resyncs.forEach { ctrlIdx ->
                conn.slotIdForIndex(ctrlIdx)?.let { slotId -> syncSlot(id, slotId) }
            }
        }

        /**
         * Authenticated close-notify (0x000F): the session is gone server-side
         * RIGHT NOW: no death-timeout wait. The reason picks the follow-up:
         * unpaired is terminal (re-pair needed); a rotation-superseded session
         * stays down (its replacement is already live); shutdown/kick retry on
         * the backoff curve (the kick is transient by design).
         */
        private fun handleServerClose(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            reason: Int,
            session: SessionTicket,
        ) {
            val id = conn.id
            ifSessionCurrent(conn, session) {
                val closedAt = disconnectAt(id)
                when (reason) {
                    SatelliteConnection.CLOSE_REASON_UNPAIRED -> {
                        store.forgetSatelliteSharedKey(id)
                        markStale(id)
                    }
                    SatelliteConnection.CLOSE_REASON_REPLACED -> Unit
                    else -> scheduleRetry(conn, server, ConnectIntent.RETRY_AFTER_DEATH, closedAt)
                }
            }
        }

        // Bounded exponential backoff for the silent retry paths; a user tap
        // resets the curve. Never schedules for USER_INITIATED: the user gets
        // immediate feedback instead of a background loop they didn't ask for.
        // Called under transitionLock, in the step that ended the session, so a disconnect lands
        // either before (and nothing is scheduled) or after (and cancels it). [generation] is the one
        // the session ended under: a disconnect after it keeps even a retry already firing from dialling.
        private fun scheduleRetry(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
            generation: Int,
        ) {
            if (intent == ConnectIntent.USER_INITIATED) return
            val id = conn.id
            val attempt = retryAttempts.merge(id, 1, Int::plus) ?: 1
            val delayMs =
                (RETRY_BASE_MS shl (attempt - 1).coerceAtMost(RETRY_MAX_SHIFT))
                    .coerceAtMost(RETRY_MAX_MS)
            val retry =
                scope.launch {
                    kotlinx.coroutines.delay(delayMs)
                    if (_connections.value[id]?.state?.value == SatelliteSessionState.Idle &&
                        id !in _staleSatelliteIds.value
                    ) {
                        val target = store.remembered().firstOrNull { it.id == id }?.toDiscovered() ?: server
                        ifCurrent(id, generation) { connect(target, ConnectIntent.RETRY_AFTER_DEATH) }
                    }
                }
            trackPendingRetry(id, retry)
        }

        private fun trackPendingRetry(
            id: String,
            retry: Job,
        ) {
            val pending = pendingRetries.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }
            pending += retry
            retry.invokeOnCompletion { pending -= retry }
        }

        /**
         * Heartbeat acks said the server's topology no longer matches ours
         * (epoch/bitmap drift). GET the applied state; if it actually matches
         * the desired set, just adopt the epoch (a benign drift, e.g. our own
         * standalone PUT raced an ack). Otherwise re-PUT the full desired state.
         * The declarative converge makes the retry free.
         */
        private suspend fun reconcile(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            session: SessionTicket,
        ) {
            val id = conn.id
            if (reconcileInFlight.putIfAbsent(id, true) != null) return
            try {
                val live = liveSessionFor(conn, session) ?: return
                val view = fetchSessionView(live) ?: return
                when {
                    rejectsOurCredentials(view.code) -> dropRejectedSession(conn, session)
                    view.connectionId == live.connectionId && conn.matchesAppliedView(view) ->
                        ifSessionCurrent(conn, session) { conn.adoptEpoch(view.epoch) }
                    // Applied ≠ desired (or the session is gone): converge with a fresh session
                    // PUT. Tear the UDP tuple down first: the PUT rotates token/key.
                    else ->
                        if (ifSessionCurrent(conn, session) { conn.restartForFreshPut() } != null) {
                            openSession(conn, server, ConnectIntent.RETRY_AFTER_DEATH, session.generation)
                        }
                }
            } finally {
                reconcileInFlight.remove(id)
            }
        }

        // A connection that can be spoken to over REST right now: its UDP tuple is up and
        // the shared key is still remembered. Null is any of those missing, which every
        // converge step treats as "nothing to do this round".
        private class LiveSession(
            val conn: SatelliteConnection,
            val session: SessionTicket,
            val server: DiscoveredServer,
            val proof: String,
        ) {
            val connectionId: String get() = session.connectionId
        }

        // Null too once the connection no longer holds [session]: a disconnect or a fresh PUT
        // overtook the caller, and there is nothing of that session left to ask the satellite about.
        private fun liveSessionFor(
            conn: SatelliteConnection,
            session: SessionTicket,
        ): LiveSession? {
            if (conn.connectionId != session.connectionId) return null
            val creds = credentialsFor(conn.id) ?: return null
            return LiveSession(conn, session, conn.server.value, creds.proof)
        }

        private suspend fun fetchSessionView(live: LiveSession): SessionViewDto? {
            val raw =
                replyOrNull {
                    discoveryRepo.getSession(live.server.ip, live.server.httpPort, live.connectionId, deviceId, live.proof)
                }?.takeIf { !it.unreachable }?.body ?: return null
            return runCatching { json.decodeFromString(SessionViewDto.serializer(), raw) }.getOrNull()
        }

        private suspend fun putControllerFor(
            live: LiveSession,
            descriptor: ControllerDescriptor,
        ): ControllerPutResponse? {
            val raw =
                replyOrNull {
                    discoveryRepo.putController(
                        live.server.ip,
                        live.server.httpPort,
                        live.connectionId,
                        descriptor.ctrlIdx,
                        deviceId,
                        live.proof,
                        descriptor.toJson(),
                    )
                }?.takeIf { !it.unreachable }?.body ?: return null
            return runCatching { json.decodeFromString(ControllerPutResponse.serializer(), raw) }.getOrNull()
        }

        // The satellite no longer knows us (unpaired there, or our proof stopped matching):
        // the session is over and the key is worthless, and the row reads Stale until re-paired.
        // False, touching nothing, when a disconnect overtook the caller: the answer is about a
        // session that is gone, and a newer handshake may own the connection by then.
        private fun dropRejectedSession(
            conn: SatelliteConnection,
            generation: Int,
        ): Boolean {
            if (!markDisconnectedIfCurrent(conn, generation)) return false
            store.forgetSatelliteSharedKey(conn.id)
            markStale(conn.id)
            return true
        }

        // As above, for an answer about a live session: it touches nothing once that session is gone.
        private fun dropRejectedSession(
            conn: SatelliteConnection,
            session: SessionTicket,
        ) {
            if (ifSessionCurrent(conn, session) { conn.markDisconnected() } == null) return
            store.forgetSatelliteSharedKey(conn.id)
            markStale(conn.id)
        }

        // The send counter crossed the re-PUT threshold: converge with a fresh
        // session PUT for new token/salt/key (counter back to 1). Reconcile
        // can't carry this — its matched-view early-exit adopts the epoch
        // without rotating anything.
        private suspend fun rekey(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            session: SessionTicket,
        ) {
            val restarted = ifSessionCurrent(conn, session) { conn.restartForFreshPutIfLive() } == true
            if (restarted) openSession(conn, server, ConnectIntent.RETRY_AFTER_DEATH, session.generation)
        }

        // Single-slot converge while the session is live (PUT .../controllers/{idx}).
        // The session (and its UDP keys) never churn for a toggle.
        private suspend fun syncSlot(
            id: String,
            slotId: String,
        ) {
            val conn = _connections.value[id]?.takeIf { it.state.value == SatelliteSessionState.Live } ?: return
            val connectionId = conn.connectionId ?: return
            val live = liveSessionFor(conn, SessionTicket(generationOf(id), connectionId)) ?: return
            val descriptor = conn.descriptorFor(slotId) ?: return
            val resp = putControllerFor(live, descriptor) ?: return
            when {
                rejectsOurCredentials(resp.code) -> dropRejectedSession(conn, live.session)
                // 404 connection-not-found: the session died under us; the
                // alive-poll/close-notify path owns recovery. Nothing to fold in.
                resp.controller == null -> Unit
                else -> foldControllerPut(live, resp.epoch, resp.controller)
            }
        }

        private suspend fun foldControllerPut(
            live: LiveSession,
            epoch: Int,
            result: ControllerApplyDto,
        ) {
            val conn = live.conn
            val folded =
                ifSessionCurrent(conn, live.session) {
                    conn.adoptEpoch(epoch)
                    conn.applyResults(listOf(result), onApplyFailures = { failures -> reportApplyFailures(live.server.name, failures) })
                } != null
            if (folded && conn.wantsMouseControl() != conn.mouseControlGranted) {
                // The toggle changed the session-level desire, but the grant is
                // only computed at session PUT (contract §hostFeatures).
                // Converge the full session so the request rides along.
                reconcile(conn, live.server, live.session)
            }
        }

        // Slot delete while live (DELETE .../controllers/{idx}): removes the
        // SLOT only; the session lives on (zero-controller sessions are valid).
        private suspend fun deleteSlot(
            id: String,
            ctrlIdx: Int,
        ) {
            val conn = _connections.value[id] ?: return
            val connectionId = conn.connectionId ?: return
            val live = liveSessionFor(conn, SessionTicket(generationOf(id), connectionId)) ?: return
            val raw =
                replyOrNull {
                    discoveryRepo.deleteController(
                        live.server.ip,
                        live.server.httpPort,
                        live.connectionId,
                        ctrlIdx,
                        deviceId,
                        live.proof,
                    )
                }?.takeIf { !it.unreachable }?.body ?: return
            val resp =
                runCatching { json.decodeFromString(ControllerPutResponse.serializer(), raw) }
                    .getOrNull() ?: return
            if (resp.error == null) ifSessionCurrent(conn, live.session) { conn.adoptEpoch(resp.epoch) }
        }

        private fun unreachableMessage(reply: HttpReply?): ConnectionError =
            if (reply?.pinMismatch == true) ConnectionError.IdentityChanged else ConnectionError.ServerUnreachable

        private suspend fun failSession(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            intent: ConnectIntent,
            error: ConnectionError,
            retry: Boolean,
            generation: Int,
        ) {
            val ended =
                ifCurrent(conn.id, generation) {
                    conn.markDisconnected()
                    if (retry) scheduleRetry(conn, server, intent, generation)
                } != null
            if (ended) emitErrorIfUserInitiated(intent, error)
        }

        private suspend fun failUserHandshake(
            conn: SatelliteConnection,
            server: DiscoveredServer,
            error: ConnectionError,
            generation: Int,
        ) = failSession(conn, server, ConnectIntent.USER_INITIATED, error, retry = false, generation)

        private suspend fun emitErrorIfUserInitiated(
            intent: ConnectIntent,
            error: ConnectionError,
        ) {
            if (intent == ConnectIntent.USER_INITIATED) {
                _events.emit(ConnectionEvent.Error(error))
            }
        }

        private fun markStale(id: String) {
            _staleSatelliteIds.update { if (id in it) it else it + id }
        }

        private fun clearStale(id: String) {
            _staleSatelliteIds.update { if (id in it) it - id else it }
        }

        // The user's disconnect: it also holds the satellite down against the app's own reconnects.
        fun disconnect(id: String) {
            synchronized(transitionLock) {
                userDisconnected += id
                disconnectAt(id)
            }
        }

        // Returns the generation this disconnect began: the one a retry scheduled after it is judged by.
        private fun disconnectAt(id: String): Int =
            synchronized(transitionLock) {
                // Stop any reverse-pairing poll and silent retry first: otherwise either could
                // call openSession seconds after the user tore the connection down.
                approvalPollJobs.remove(id)?.cancel()
                pendingRetries.remove(id)?.forEach { it.cancel() }
                val generation = generationOf(id) + 1
                disconnectGenerations[id] = generation
                val conn = _connections.value[id] ?: return generation
                val srv = conn.server.value
                val cid = conn.connectionId
                conn.markDisconnected()
                val proof = cid?.let { credentialsFor(id)?.proof }
                if (cid != null && proof != null) {
                    scope.launch(ioDispatcher) { releaseSession(srv, cid, proof) }
                }
                generation
            }

        // A session PUT that answered after a disconnect: whatever it was granted goes straight back.
        private suspend fun releaseStaleGrant(
            server: DiscoveredServer,
            reply: HttpReply?,
            proof: String,
        ) {
            val body = reply?.takeIf { !it.unreachable }?.body ?: return
            val granted = runCatching { json.decodeFromString(SessionResponse.serializer(), body) }.getOrNull()
            val connectionId = granted?.connectionId ?: return
            releaseSession(server, connectionId, proof)
        }

        private suspend fun releaseSession(
            server: DiscoveredServer,
            connectionId: String,
            proof: String,
        ) {
            replyOrNull { discoveryRepo.disconnect(server.ip, server.httpPort, connectionId, deviceId, proof) }
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
                    replyOrNull { discoveryRepo.unpair(srv.ip, srv.httpPort, deviceId, proof) }
                }
            }
            disconnect(id)
            userDisconnected.remove(id)
            store.forgetSatellite(id)
            clearStale(id)
            retryAttempts.remove(id)
            negotiatedProtocol.remove(id)
            _connections.update { it - id }
        }

        private fun getOrCreateDeviceId(): String {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return p.getString(KEY_DEVICE_ID, null) ?: java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .also { p.edit { putString(KEY_DEVICE_ID, it) } }
        }

        companion object {
            private const val PREFS_NAME = "satellite"
            private const val KEY_DEVICE_ID = "deviceId"

            private const val DISC_PORT = 9879
            private const val DISC_TIMEOUT_MS = 4000

            // Bounded exponential backoff for silent reconnects: 1s, 2s, 4s …
            // capped at 60s. A momentary Wi-Fi drop self-heals fast; a real
            // outage stops hammering the satellite's buffers.
            private const val RETRY_BASE_MS = 1000L
            private const val RETRY_MAX_MS = 60_000L
            private const val RETRY_MAX_SHIFT = 6

            private const val HTTP_CONFLICT = 409

            private const val APPROVAL_POLL_INTERVAL_MS = 2000L

            // Matches the satellite's 2-minute pairing-request TTL. Stop waiting
            // once the request can no longer be accepted on the other side.
            private const val APPROVAL_TIMEOUT_MS = 120_000L
        }
    }
