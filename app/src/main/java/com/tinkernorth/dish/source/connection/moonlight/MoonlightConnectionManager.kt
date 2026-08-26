// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightCrypto
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.MoonlightPairing
import com.tinkernorth.dish.core.net.moonlight.MoonlightUrls
import com.tinkernorth.dish.core.net.moonlight.MoonlightXml
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class MoonlightConnectionEvent {
    /** The dish generated [pin]; the user must type it into the host's web UI. */
    data class PairingPinReady(
        val host: MoonlightHost,
        val pin: String,
    ) : MoonlightConnectionEvent()

    data class Error(
        val message: String,
    ) : MoonlightConnectionEvent()

    data class Paired(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()

    /**
     * The host refused to start an app because one is already running. When
     * [resumable] the dish can take that session over; when it is not, the app
     * belongs to somebody else and the only way forward is to quit it (see
     * [MoonlightConnectionManager.quitHostApp]).
     */
    data class AppAlreadyRunning(
        val host: MoonlightHost,
        val resumable: Boolean,
    ) : MoonlightConnectionEvent()
}

/**
 * Orchestrates the Moonlight host path: discovery, PIN pairing, app launch, the
 * RTSP stream setup, and the live control session. The sibling of
 * [com.tinkernorth.dish.source.connection.SatelliteConnectionManager]; it holds
 * the same shape (a connections map, a discovered list, an events flow) so the
 * composer and coordinator treat both paths uniformly.
 *
 * The launch/stream flow runs against a live Sunshine host end to end: /launch
 * (or /resume when the host already has our session), the RTSP handshake, the
 * media-port pings that stop the host's initial-ping deadline, the ENet connect
 * and the live control stream. The protocol pieces it composes are unit-tested
 * byte-for-byte against Wolf's vectors.
 */
@Singleton
class MoonlightConnectionManager
    @Inject
    constructor(
        @ApplicationContext private val context: android.content.Context,
        private val scope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val discovery: MdnsMoonlightDiscovery,
        private val gateway: MoonlightHttpGateway,
        private val identity: MoonlightIdentity,
        private val store: RememberedMoonlightRepository,
    ) {
        private val _connections = MutableStateFlow<Map<String, MoonlightConnection>>(emptyMap())
        val connections: StateFlow<Map<String, MoonlightConnection>> = _connections.asStateFlow()

        private val _discovered = MutableStateFlow<List<MoonlightHost>>(emptyList())
        val discovered: StateFlow<List<MoonlightHost>> = _discovered.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _events =
            MutableSharedFlow<MoonlightConnectionEvent>(
                replay = 0,
                extraBufferCapacity = 8,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val events: SharedFlow<MoonlightConnectionEvent> = _events.asSharedFlow()

        val remembered: StateFlow<List<RememberedMoonlight>> get() = store.entries

        private val deviceId by lazy { getOrCreateUniqueId() }

        fun get(id: String): MoonlightConnection? = _connections.value[id]

        fun startDiscovery() {
            if (!_isScanning.compareAndSet(expect = false, update = true)) return
            scope.launch {
                _discovered.value = runCatching { discovery.discover(DISCOVERY_TIMEOUT_MS) }.getOrDefault(emptyList())
                _isScanning.value = false
            }
        }

        /** Probe a manually typed address and add it if it answers /serverinfo. */
        fun addManualHost(address: String) {
            scope.launch(ioDispatcher) {
                val info =
                    gateway
                        .getHttp(MoonlightUrls.serverInfoHttp(address, MoonlightHost.DEFAULT_HTTP_PORT, deviceId))
                        .takeIf { it.ok }
                        ?.let { MoonlightXml.parseServerInfo(it.body) }
                if (info == null) {
                    _events.emit(MoonlightConnectionEvent.Error("No Moonlight host answered at $address."))
                    return@launch
                }
                val host =
                    MoonlightHost(
                        name = info.hostname.ifEmpty { address },
                        address = address,
                        httpPort = externalPortOr(info),
                        httpsPort = info.httpsPort ?: MoonlightHost.DEFAULT_HTTPS_PORT,
                        uniqueId = info.uniqueId,
                        manual = true,
                    )
                _discovered.updateAndGetHost(host)
            }
        }

        private fun MutableStateFlow<List<MoonlightHost>>.updateAndGetHost(host: MoonlightHost) {
            value = (value.filterNot { it.id == host.id } + host)
        }

        private fun externalPortOr(info: MoonlightXml.ServerInfo): Int = info.externalPort ?: MoonlightHost.DEFAULT_HTTP_PORT

        private fun findOrCreate(host: MoonlightHost): MoonlightConnection {
            val id = host.id
            return _connections
                .updateAndGet { map ->
                    if (map.containsKey(id)) map else map + (id to MoonlightConnection(id, host, scope, ioDispatcher))
                }[id]!!
        }

        /**
         * One-tap path: pair (if needed), pick the remembered/first app, and
         * launch [emulatedType] on [host]. The connections screen drives the
         * explicit pair/app-pick/type-pick steps via [pairHost], [fetchApps] and
         * [launch]; this convenience path is kept for a remembered host.
         */
        fun connect(
            host: MoonlightHost,
            emulatedType: Int,
        ) {
            val conn = findOrCreate(host)
            conn.updateHost(host)
            conn.markLaunching()
            scope.launch(ioDispatcher) {
                if (!isPaired(host) && !pair(host)) {
                    conn.markDisconnected()
                    return@launch
                }
                val appId = store.get(host.id)?.lastAppId?.takeIf { it.isNotEmpty() } ?: defaultAppId(host)
                if (appId == null) {
                    conn.markDisconnected()
                    _events.emit(MoonlightConnectionEvent.Error("No apps available on ${host.name}."))
                    return@launch
                }
                launchAndStream(conn, host, appId, emulatedType)
            }
        }

        /** Launch a specific [appId] with [emulatedType] (the app-pick path). */
        fun launch(
            host: MoonlightHost,
            appId: String,
            emulatedType: Int,
        ) {
            val conn = findOrCreate(host)
            conn.updateHost(host)
            conn.markLaunching()
            scope.launch(ioDispatcher) { launchAndStream(conn, host, appId, emulatedType) }
        }

        /**
         * Pair with [host]: emits [MoonlightConnectionEvent.PairingPinReady] with
         * the generated PIN, runs the 5 phases, and returns true when paired.
         * Public so the connections screen can await pairing before fetching the
         * app list.
         */
        suspend fun pairHost(host: MoonlightHost): Boolean =
            withContext(ioDispatcher) {
                if (isPaired(host)) {
                    _events.emit(MoonlightConnectionEvent.Paired(host))
                    true
                } else {
                    pair(host)
                }
            }

        /** Fetch the host's app list (empty when unreachable/unpaired). */
        suspend fun fetchApps(host: MoonlightHost): List<MoonlightXml.App> =
            withContext(ioDispatcher) {
                val reply = gateway.getHttps(MoonlightUrls.appList(host.address, host.httpsPort, deviceId), host.id)
                MoonlightXml.parseAppList(reply.body)
            }

        private fun isPaired(host: MoonlightHost): Boolean {
            val reply = gateway.getHttps(MoonlightUrls.serverInfoHttps(host.address, host.httpsPort, deviceId), host.id)
            if (!reply.ok) return false
            return MoonlightXml.parseServerInfo(reply.body)?.paired == true
        }

        /** Runs the 5-phase pairing; phase 1 blocks until the user enters the PIN. */
        @Suppress("ReturnCount") // each early return is a distinct phase-failure bail
        private suspend fun pair(host: MoonlightHost): Boolean {
            val pin = randomPin()
            _events.emit(MoonlightConnectionEvent.PairingPinReady(host, pin))
            val pairing = MoonlightPairing(identity, pin)
            return runCatching {
                // Phase 1 (HTTP): the host prompts for the PIN and blocks until
                // entered, so this one waits on a human rather than on the network.
                val p1 =
                    gateway.getHttp(
                        MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase1Params(deviceId)),
                        MoonlightHttpGateway.PAIR_PIN_TIMEOUT_MS,
                    )
                val cert = MoonlightXml.parsePairReply(p1.body)?.plainCert ?: return false
                pairing.onPhase1(
                    String(
                        com.tinkernorth.dish.core.net
                            .hexToBytes(cert),
                        Charsets.US_ASCII,
                    ),
                )

                val p2 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase2Params(deviceId)))
                val challenge = MoonlightXml.parsePairReply(p2.body)?.challengeResponse ?: return false
                if (!pairing.onPhase2(challenge)) return false

                val p3 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase3Params(deviceId)))
                val secret = MoonlightXml.parsePairReply(p3.body)?.pairingSecret ?: return false
                if (!pairing.onPhase3(secret)) return false

                val p4 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase4Params(deviceId)))
                if (MoonlightXml.parsePairReply(p4.body)?.paired != true) return false

                // Phase 5 (HTTPS): confirm the client-cert-authenticated channel.
                gateway.getHttps(MoonlightUrls.pairHttps(host.address, host.httpsPort, pairing.phase5Params(deviceId)), host.id)
                rememberPaired(host)
                _events.emit(MoonlightConnectionEvent.Paired(host))
                true
            }.getOrElse {
                Log.w(TAG, "pairing failed for ${host.address}: ${it.message}")
                _events.emit(MoonlightConnectionEvent.Error("Pairing failed. Confirm the PIN on the host and try again."))
                false
            }
        }

        private suspend fun launchAndStream(
            conn: MoonlightConnection,
            host: MoonlightHost,
            appId: String,
            emulatedType: Int,
        ) {
            val rikey = MoonlightCrypto.randomBytes(RIKEY_LEN)
            val rikeyId =
                MoonlightCrypto.randomBytes(4).let {
                    (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or
                        ((it[2].toInt() and 0xFF) shl 16) or ((it[3].toInt() and 0xFF) shl 24)
                }
            val rtspPort = openSession(conn, host, appId, bytesToHex(rikey), rikeyId) ?: return
            val rtsp = MoonlightRtspClient(host.address, rtspPort).handshake(LAUNCH_WIDTH, LAUNCH_HEIGHT, LAUNCH_FPS)
            if (rtsp == null) {
                // MoonlightRtspClient has already said which step failed and how.
                Log.w(TAG, "RTSP setup failed on ${host.address}:$rtspPort")
                giveUp(conn, host, "Stream setup failed on ${host.name}.")
                return
            }
            // Before the control channel, not after: the host counts its initial
            // ping deadline from its own session start, so the media ports get
            // their first datagram at the earliest moment we know their numbers.
            runCatching { UdpMediaPinger(host.address, rtsp.videoPort, rtsp.audioPort, rtsp.pingPayload) }
                .onSuccess(conn::startMediaPings)
                .onFailure { Log.w(TAG, "no media ping sockets for ${host.address}: ${it.message}") }
            val transport =
                runCatching { UdpControlTransport(host.address, rtsp.controlPort) }
                    .onFailure { Log.w(TAG, "no control socket to ${host.address}:${rtsp.controlPort}: ${it.message}") }
                    .getOrNull()
            if (transport == null) {
                giveUp(conn, host, "Control channel did not connect on ${host.name}.")
                return
            }
            val session =
                MoonlightControlSession(rikey, rtsp.enetConnectData, transport, System::currentTimeMillis) { event ->
                    conn.dispatchFeedback(event)
                }
            if (!session.connect()) {
                Log.w(TAG, "control channel refused on ${host.address}:${rtsp.controlPort}")
                giveUp(conn, host, "Control channel did not connect on ${host.name}.")
                return
            }
            Log.i(TAG, "live on ${host.address}, control ${rtsp.controlPort}, emulated type $emulatedType")
            conn.markLive(
                session,
                emulatedType,
                MoonlightConnection.BASE_CAPABILITIES,
                MoonlightConnection.SUPPORTED_BUTTONS,
            )
            rememberPaired(host, appId, emulatedType)
        }

        /**
         * Ask the host to start [appId] and hand back the RTSP port it named, or
         * null when it would not.
         *
         * A MOONLIGHT HOST REFUSES IN THE BODY, NOT IN THE STATUS LINE. Sunshine
         * answers a second /launch with HTTP 200 carrying
         * `status_code="400" status_message="An app is already running on this
         * host"`, so the transport succeeded and the call did not. Reading only
         * the HTTP status turned that into "RTSP port null" and a generic
         * failure, which named the symptom and hid the cause.
         */
        private suspend fun openSession(
            conn: MoonlightConnection,
            host: MoonlightHost,
            appId: String,
            rikeyHex: String,
            rikeyId: Int,
        ): Int? {
            val url = MoonlightUrls.launch(host.address, host.httpsPort, deviceId, appId, rikeyHex, rikeyId, LAUNCH_MODE)
            val reply = gateway.getHttps(url, host.id)
            val status = MoonlightXml.parseStatus(reply.body)
            val rtspPort = parseRtspPort(reply.body)
            Log.i(
                TAG,
                "launch $appId on ${host.address}: HTTP ${reply.status}, " +
                    "host ${status?.code ?: "?"} ${status?.message.orEmpty()}, RTSP port $rtspPort",
            )
            if (reply.ok && status?.ok != false && rtspPort != null) return rtspPort
            if (status?.appAlreadyRunning == true) return resumeSession(conn, host, status, rikeyHex, rikeyId)
            Log.w(TAG, "launch refused by ${host.address}: ${reply.body.take(BODY_LOG_CHARS)}")
            conn.markFailed()
            _events.emit(MoonlightConnectionEvent.Error(refusalMessage(host, status)))
            return null
        }

        /**
         * Take over the session the host already has, when it says we may. A host
         * that says we may not is holding somebody else's app and the only way
         * past it is [quitHostApp], so say so instead of failing vaguely.
         */
        private suspend fun resumeSession(
            conn: MoonlightConnection,
            host: MoonlightHost,
            launchStatus: MoonlightXml.Status,
            rikeyHex: String,
            rikeyId: Int,
        ): Int? {
            if (!launchStatus.resume) {
                Log.i(TAG, "${host.address} has an app running and will not resume it")
                conn.markFailed()
                _events.emit(MoonlightConnectionEvent.AppAlreadyRunning(host, resumable = false))
                return null
            }
            val reply =
                gateway.getHttps(MoonlightUrls.resume(host.address, host.httpsPort, deviceId, rikeyHex, rikeyId), host.id)
            val status = MoonlightXml.parseStatus(reply.body)
            val rtspPort = parseRtspPort(reply.body)
            Log.i(
                TAG,
                "resume on ${host.address}: HTTP ${reply.status}, " +
                    "host ${status?.code ?: "?"} ${status?.message.orEmpty()}, RTSP port $rtspPort",
            )
            if (reply.ok && status?.ok != false && rtspPort != null) return rtspPort
            Log.w(TAG, "resume refused by ${host.address}: ${reply.body.take(BODY_LOG_CHARS)}")
            conn.markFailed()
            _events.emit(MoonlightConnectionEvent.AppAlreadyRunning(host, resumable = true))
            return null
        }

        /**
         * Tell [host] to end the app it is running. The protocol's own way out of
         * "an app is already running", and the only one when the host will not
         * resume that session for us.
         */
        fun quitHostApp(host: MoonlightHost) {
            scope.launch(ioDispatcher) {
                val ended = cancelHostApp(host)
                _events.emit(
                    if (ended) {
                        MoonlightConnectionEvent.Error("Closed the app running on ${host.name}. Try again.")
                    } else {
                        MoonlightConnectionEvent.Error("Couldn't close the app running on ${host.name}.")
                    },
                )
            }
        }

        private fun cancelHostApp(host: MoonlightHost): Boolean {
            val reply = gateway.getHttps(MoonlightUrls.cancel(host.address, host.httpsPort, deviceId), host.id)
            val status = MoonlightXml.parseStatus(reply.body)
            Log.i(TAG, "cancel on ${host.address}: HTTP ${reply.status}, host ${status?.code ?: "?"}")
            return reply.ok && status?.ok != false
        }

        /**
         * Abandon a launch we asked for and could not use. The host started an app
         * on our behalf, so we take it back down rather than strand it: every later
         * attempt would otherwise be refused by the app we ourselves left running.
         *
         * Only for the setup path. A control stream that drops after going live is
         * left alone, because the host will let us /resume it and the user would
         * rather have that than have their game closed under them.
         */
        private suspend fun giveUp(
            conn: MoonlightConnection,
            host: MoonlightHost,
            message: String,
        ) {
            conn.markFailed()
            runCatching { cancelHostApp(host) }
            _events.emit(MoonlightConnectionEvent.Error(message))
        }

        private fun refusalMessage(
            host: MoonlightHost,
            status: MoonlightXml.Status?,
        ): String =
            status
                ?.message
                ?.takeIf { it.isNotEmpty() }
                ?.let { "${host.name} refused the session: $it" }
                ?: "Couldn't start a session on ${host.name}."

        private fun defaultAppId(host: MoonlightHost): String? {
            val reply = gateway.getHttps(MoonlightUrls.appList(host.address, host.httpsPort, deviceId), host.id)
            return MoonlightXml.parseAppList(reply.body).firstOrNull()?.id
        }

        fun disconnect(id: String) {
            _connections.value[id]?.markDisconnected()
        }

        fun forget(id: String) {
            disconnect(id)
            store.remove(id)
            _connections.updateAndGet { it - id }
        }

        private fun rememberPaired(
            host: MoonlightHost,
            appId: String = store.get(host.id)?.lastAppId.orEmpty(),
            emulatedType: Int = store.get(host.id)?.emulatedType ?: MoonlightEmulatedType.AUTO,
        ) {
            store.put(
                RememberedMoonlight(
                    id = host.id,
                    name = host.name,
                    address = host.address,
                    httpPort = host.httpPort,
                    httpsPort = host.httpsPort,
                    uniqueId = host.uniqueId,
                    lastAppId = appId,
                    emulatedType = emulatedType,
                ),
            )
        }

        /** The remembered emulated-device pick for [hostId], defaulting to Auto. */
        fun rememberedEmulatedType(hostId: String): Int = store.get(hostId)?.emulatedType ?: MoonlightEmulatedType.AUTO

        /** The remembered last-launched app id for [hostId], or empty. */
        fun rememberedAppId(hostId: String): String = store.get(hostId)?.lastAppId.orEmpty()

        // The /launch response carries sessionUrl0 = rtsp://ip:port; pull the port.
        private fun parseRtspPort(xml: String): Int? =
            Regex("rtsp://[^:<]+:(\\d+)")
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        private fun randomPin(): String {
            val n = java.security.SecureRandom().nextInt(PIN_RANGE)
            return "%04d".format(n)
        }

        private fun getOrCreateUniqueId(): String {
            val prefs = context.getSharedPreferences("moonlight", android.content.Context.MODE_PRIVATE)
            return prefs.getString("uniqueid", null) ?: java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(16)
                .also { id -> prefs.edit { putString("uniqueid", id) } }
        }

        private companion object {
            const val TAG = "MoonlightConnectionMgr"
            const val DISCOVERY_TIMEOUT_MS = 4000
            const val RIKEY_LEN = 16
            const val PIN_RANGE = 10_000
            const val LAUNCH_MODE = "1280x720x30"
            const val LAUNCH_WIDTH = 1280
            const val LAUNCH_HEIGHT = 720
            const val LAUNCH_FPS = 30
            const val BODY_LOG_CHARS = 256
        }
    }
