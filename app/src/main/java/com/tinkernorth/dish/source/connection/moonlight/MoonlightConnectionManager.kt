// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightCrypto
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
}

/**
 * Orchestrates the Moonlight host path: discovery, PIN pairing, app launch, the
 * RTSP stream setup, and the live control session. The sibling of
 * [com.tinkernorth.dish.source.connection.SatelliteConnectionManager]; it holds
 * the same shape (a connections map, a discovered list, an events flow) so the
 * composer and coordinator treat both paths uniformly.
 *
 * The end-to-end launch/stream flow has not been exercised against a live host
 * in this change (see the PR's known gaps); the protocol pieces it composes are
 * unit-tested byte-for-byte against Wolf's vectors.
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

        /** Pair with (if needed) and launch [emulatedType] on [host]. */
        fun connect(
            host: MoonlightHost,
            emulatedType: Int,
        ) {
            val conn = findOrCreate(host)
            conn.updateHost(host)
            conn.markLaunching()
            scope.launch(ioDispatcher) {
                val paired = isPaired(host)
                if (!paired && !pair(host)) {
                    conn.markDisconnected()
                    return@launch
                }
                launchAndStream(conn, host, emulatedType)
            }
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
                // Phase 1 (HTTP): the host prompts for the PIN and blocks until entered.
                val p1 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase1Params(deviceId)))
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
            emulatedType: Int,
        ) {
            val rikey = MoonlightCrypto.randomBytes(RIKEY_LEN)
            val rikeyId =
                MoonlightCrypto.randomBytes(4).let {
                    (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or
                        ((it[2].toInt() and 0xFF) shl 16) or ((it[3].toInt() and 0xFF) shl 24)
                }
            val appId =
                store.get(host.id)?.lastAppId?.takeIf { it.isNotEmpty() } ?: defaultAppId(host) ?: run {
                    conn.markDisconnected()
                    _events.emit(MoonlightConnectionEvent.Error("No apps available on ${host.name}."))
                    return
                }
            val launchUrl =
                MoonlightUrls.launch(host.address, host.httpsPort, deviceId, appId, bytesToHex(rikey), rikeyId, LAUNCH_MODE)
            val launchReply = gateway.getHttps(launchUrl, host.id)
            val rtspPort = parseRtspPort(launchReply.body)
            if (!launchReply.ok || rtspPort == null) {
                conn.markDisconnected()
                _events.emit(MoonlightConnectionEvent.Error("Couldn't start a session on ${host.name}."))
                return
            }
            val rtsp = MoonlightRtspClient(host.address, rtspPort).handshake(LAUNCH_WIDTH, LAUNCH_HEIGHT, LAUNCH_FPS)
            if (rtsp == null) {
                conn.markDisconnected()
                _events.emit(MoonlightConnectionEvent.Error("Stream setup failed on ${host.name}."))
                return
            }
            val transport = runCatching { UdpControlTransport(host.address, rtsp.controlPort) }.getOrNull()
            if (transport == null) {
                conn.markDisconnected()
                return
            }
            val session =
                MoonlightControlSession(rikey, rtsp.enetConnectData, transport, System::currentTimeMillis) { event ->
                    conn.dispatchFeedback(event)
                }
            if (!session.connect()) {
                conn.markDisconnected()
                _events.emit(MoonlightConnectionEvent.Error("Control channel did not connect on ${host.name}."))
                return
            }
            conn.markLive(
                session,
                emulatedType,
                MoonlightConnection.BASE_CAPABILITIES,
                MoonlightConnection.SUPPORTED_BUTTONS,
            )
            rememberPaired(host, appId)
        }

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
                ),
            )
        }

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
        }
    }
