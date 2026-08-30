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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Something went right and the user should hear about it. */
    data class Notice(
        val message: String,
    ) : MoonlightConnectionEvent()

    data class Paired(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()

    /**
     * Pairing ran and did not end in trust. [reason] names WHICH step gave up:
     * six different things fail this flow and they used to arrive as one
     * indistinguishable event, so a host that was unplugged mid-pairing told the
     * user to check they had typed the code into the right host.
     */
    data class PairingFailed(
        val host: MoonlightHost,
        val reason: String,
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

    /** The host said it would hand its session back and then would not. */
    data class RejoinRefused(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()

    /** The host refused for a reason of its own; [message] is its own wording. */
    data class LaunchRefused(
        val host: MoonlightHost,
        val message: String,
    ) : MoonlightConnectionEvent()

    /** The app started and the stream did not come up, so it has been cancelled again. */
    data class SetupFailed(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()

    /** The host already carries the four controllers a session can hold. */
    data class HostFull(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()

    /** The host answered under a different uniqueid, so the old pairing is dead. */
    data class HostReplaced(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()

    /** The host ended the session; nothing is recoverable without starting a new one. */
    data class EndedByHost(
        val host: MoonlightHost,
    ) : MoonlightConnectionEvent()
}

/** What a host's session must do next, pulled out of the converge for testability. */
internal enum class MoonlightConverge { OPEN, ANNOUNCE, WAIT, RELEASE, CANCEL }

/**
 * The reference count, as one rule. The first pad on a host opens the stream, later
 * pads only announce themselves on the one already up, a launch in flight is left
 * alone, and losing the last pad releases the host, closing the app it started only
 * when a session actually came up.
 */
internal fun moonlightConverge(
    state: MoonlightSessionState,
    wantedPads: Int,
): MoonlightConverge =
    when {
        wantedPads == 0 && state == MoonlightSessionState.Live -> MoonlightConverge.CANCEL
        wantedPads == 0 -> MoonlightConverge.RELEASE
        state == MoonlightSessionState.Live -> MoonlightConverge.ANNOUNCE
        state == MoonlightSessionState.Launching -> MoonlightConverge.WAIT
        else -> MoonlightConverge.OPEN
    }

/** One binding's claim on a host session: which slot, and what pad to announce for it. */
data class MoonlightPadRequest(
    val slotId: String,
    val emulatedType: Int,
    val capabilities: Int,
    val supportedButtons: Int,
)

/**
 * Orchestrates the Moonlight host path: discovery, PIN pairing, app launch, the
 * RTSP stream setup, and the live control session. The sibling of
 * [com.tinkernorth.dish.source.connection.SatelliteConnectionManager]; it holds
 * the same shape (a connections map, a discovered list, an events flow) so the
 * composer and coordinator treat both paths uniformly.
 *
 * ONE SESSION PER HOST, OWNED BY THE BINDINGS. [applyDesired] is the whole
 * lifecycle: the first pad on a host launches (or resumes) and streams, later
 * pads only announce themselves on the live stream, and the last pad leaving is
 * what sends /cancel. Nothing else starts or stops a session.
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

        /**
         * Hosts that have answered a mutual-TLS call in THIS process. There is no
         * liveness in this protocol, so "Paired" is a word that wants proof and the
         * only proof there is, is a call the host authorised. The hosts screen does
         * not probe, so without this it can only ever say "Remembered", which reads
         * as unverified straight after the user watched a pairing succeed.
         */
        private val _verifiedHostIds = MutableStateFlow<Set<String>>(emptySet())
        val verifiedHostIds: StateFlow<Set<String>> = _verifiedHostIds.asStateFlow()

        private val _sessionHostIds = MutableStateFlow<Set<String>>(emptySet())

        /** Hosts this device is holding a session open for; the foreground service follows it. */
        val sessionHostIds: StateFlow<Set<String>> = _sessionHostIds.asStateFlow()

        private val _events =
            MutableSharedFlow<MoonlightConnectionEvent>(
                replay = 0,
                extraBufferCapacity = 8,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val events: SharedFlow<MoonlightConnectionEvent> = _events.asSharedFlow()

        val remembered: StateFlow<List<RememberedMoonlight>> get() = store.entries

        private val deviceId by lazy { getOrCreateUniqueId() }

        // Serialises the whole converge so two emissions cannot both decide they are
        // the first pad on a host and launch it twice.
        private val convergeLock = Mutex()

        @Volatile private var desired: Map<String, List<MoonlightPadRequest>> = emptyMap()

        fun get(id: String): MoonlightConnection? = _connections.value[id]

        /**
         * Browse for hosts and MERGE the answer into what is already known. Assigning
         * it outright meant one mDNS miss erased every host that was only ever
         * discovered, taking any binding pointing at one down with it. Nothing here is
         * a liveness light, so a row that outlives a failed browse costs nothing.
         */
        fun startDiscovery() {
            if (!_isScanning.compareAndSet(expect = false, update = true)) return
            scope.launch {
                val found =
                    runCatching { discovery.discover(DISCOVERY_TIMEOUT_MS) }
                        .onFailure { Log.w(TAG, "discovery failed: ${it.message}", it) }
                        .getOrDefault(emptyList())
                Log.i(TAG, "discovery found ${found.size} host(s), had ${_discovered.value.size}")
                // One emission for the whole scan: every downstream composer re-derives
                // the connection list per emission, so merging host by host would rebuild
                // it once per host found.
                _discovered.value = _discovered.value.filterNot { old -> found.any { it.id == old.id } } + found
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
                    Log.w(TAG, "manual add: nothing answered /serverinfo at $address")
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
                Log.i(TAG, "manual add: ${host.name} at $address as ${host.id}")
                _discovered.mergeHost(host)
                // Typing an address is durable interest, so the host outlives the
                // discovery list it would otherwise be the only copy of.
                rememberInterest(host)
            }
        }

        private fun MutableStateFlow<List<MoonlightHost>>.mergeHost(host: MoonlightHost) {
            value = value.filterNot { it.id == host.id } + host
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
         * Re-verify what we know about [host] without touching a session. The
         * plaintext probe answers reachability and PairStatus; the mutual-TLS probe
         * is the only proof the pairing still stands, and its own currentgame is
         * the only thing that tells us whether the session on this host is ours.
         */
        suspend fun probe(host: MoonlightHost): MoonlightProbe =
            withContext(ioDispatcher) {
                val plain =
                    gateway
                        .getHttp(MoonlightUrls.serverInfoHttp(host.address, host.httpPort, deviceId))
                        .takeIf { it.ok }
                        ?.let { MoonlightXml.parseServerInfo(it.body) }
                // "Do we hold a pairing" is the PAIRED FLAG, not a non-empty uniqueid.
                // Real hosts publish no uniqueid TXT record, so reading it off that made
                // every mDNS-discovered host report M5 ("never paired") when it went
                // offline instead of M6 ("remembered, will start when it is back").
                val record = store.get(host.id)?.takeIf { it.paired }
                val storedId = record?.uniqueId.orEmpty()
                if (plain == null) {
                    return@withContext MoonlightProbe(
                        trust = if (record == null) MoonlightTrustState.UNREACHABLE else MoonlightTrustState.REMEMBERED,
                    )
                }
                if (storedId.isNotEmpty() && plain.uniqueId.isNotEmpty() && plain.uniqueId != storedId) {
                    Log.i(TAG, "${host.address} answers as ${plain.uniqueId}, remembered as $storedId: host replaced")
                    return@withContext MoonlightProbe(trust = MoonlightTrustState.REPLACED)
                }
                // THE PLAINTEXT PairStatus IS NOT AN ANSWER ABOUT PAIRING, so nothing may
                // be gated on it. Sunshine computes that field only on the mutual-TLS
                // route and hands every plaintext caller a 0: measured against the live
                // host, which reports 0 for this device's own uniqueid and 0 for one it
                // has never seen, while answering the same device's mutual-TLS call with
                // a 1. Treating the 0 as "not paired" made the probe unable to return
                // PAIRED at all, and openStream only launches on PAIRED, so no session
                // could ever start. The mutual-TLS call is the only thing that can say.
                val secure = gateway.getHttps(MoonlightUrls.serverInfoHttps(host.address, host.httpsPort, deviceId), host.id)
                if (!secure.ok) {
                    val trust = if (record == null) MoonlightTrustState.NOT_PAIRED else MoonlightTrustState.TRUST_LOST
                    Log.i(TAG, "${host.address} refused mutual TLS (HTTP ${secure.status}): $trust")
                    return@withContext MoonlightProbe(trust = trust)
                }
                val info = MoonlightXml.parseServerInfo(secure.body)
                if (info?.paired != true) {
                    val trust = if (record == null) MoonlightTrustState.NOT_PAIRED else MoonlightTrustState.TRUST_LOST
                    Log.i(TAG, "${host.address} answered mutual TLS unpaired: $trust")
                    return@withContext MoonlightProbe(trust = trust)
                }
                val apps = runCatching { fetchAppList(host) }.getOrNull()
                markVerified(host.id)
                MoonlightProbe(
                    trust = MoonlightTrustState.PAIRED,
                    apps = apps.orEmpty(),
                    appsFetched = apps != null,
                    appsFailed = apps == null,
                    ownSession = info.currentGame != 0,
                    currentAppId = info.currentGame.takeIf { it != 0 }?.toString(),
                )
            }

        /**
         * Converge every host's session on the pads its bindings ask for. The only
         * entry point into the session lifecycle: a host that gains its first pad is
         * launched, a host that keeps pads only gains and loses them on the live
         * stream, and a host that loses its last pad is cancelled.
         */
        fun applyDesired(desired: Map<String, List<MoonlightPadRequest>>) {
            this.desired = desired
            Log.i(TAG, "desired pads: ${desired.entries.joinToString { "${it.key}=${it.value.size}" }.ifEmpty { "none" }}")
            converge()
        }

        /**
         * Re-run the converge against the pads the bindings already asked for. The
         * retry behind every failed-session action: nothing about the binding changed,
         * so nothing new is desired, only another attempt at what already is.
         */
        fun retrySessions() = converge()

        private fun converge() {
            val desired = this.desired
            scope.launch(ioDispatcher) {
                convergeLock.withLock {
                    for ((hostId, pads) in desired) {
                        if (pads.isEmpty()) continue
                        runCatching { convergeHost(hostId, pads) }
                            .onFailure { Log.w(TAG, "converge failed for $hostId: ${it.message}", it) }
                    }
                    for (hostId in _connections.value.keys - desired.filterValues { it.isNotEmpty() }.keys) {
                        runCatching { releaseHost(hostId) }
                            .onFailure { Log.w(TAG, "release failed for $hostId: ${it.message}", it) }
                    }
                    publishSessionHosts()
                }
            }
        }

        private suspend fun convergeHost(
            hostId: String,
            pads: List<MoonlightPadRequest>,
        ) {
            val host = hostFor(hostId)
            if (host == null) {
                // Unreachable now that a bound host is written to the store, but saying
                // so beats the silent return that made a bind look like it did nothing.
                Log.w(TAG, "no host for $hostId; ${pads.size} pad(s) cannot be placed")
                return
            }
            val conn = findOrCreate(host)
            conn.updateHost(host)
            val wanted = pads.associateBy { it.slotId }
            for (slotId in conn.pads.value.keys - wanted.keys) conn.releasePad(slotId)
            when (moonlightConverge(conn.state.value, wanted.size)) {
                MoonlightConverge.WAIT -> Unit
                MoonlightConverge.OPEN -> {
                    seedPads(conn, wanted.values)
                    openStream(conn, host)
                }
                MoonlightConverge.ANNOUNCE -> announcePads(conn, host, wanted.values)
                MoonlightConverge.RELEASE, MoonlightConverge.CANCEL -> releaseHost(hostId)
            }
        }

        private suspend fun announcePads(
            conn: MoonlightConnection,
            host: MoonlightHost,
            pads: Collection<MoonlightPadRequest>,
        ) {
            for (pad in pads) {
                if (conn.padFor(pad.slotId) != null) continue
                if (!conn.hasRoom) {
                    _events.emit(MoonlightConnectionEvent.HostFull(host))
                    continue
                }
                conn.acquirePad(pad.slotId, pad.emulatedType, pad.capabilities, pad.supportedButtons)
            }
        }

        private fun seedPads(
            conn: MoonlightConnection,
            pads: Collection<MoonlightPadRequest>,
        ) {
            for (pad in pads) {
                if (!conn.hasRoom) break
                conn.acquirePad(pad.slotId, pad.emulatedType, pad.capabilities, pad.supportedButtons)
            }
        }

        private suspend fun releaseHost(hostId: String) {
            val conn = _connections.value[hostId] ?: return
            conn.pads.value.keys
                .toList()
                .forEach(conn::releasePad)
            val cancels = moonlightConverge(conn.state.value, wantedPads = 0) == MoonlightConverge.CANCEL
            conn.markDisconnected()
            if (cancels) runCatching { cancelHostApp(conn.host.value) }
        }

        private fun publishSessionHosts() {
            _sessionHostIds.value =
                _connections.value
                    .filterValues { it.pads.value.isNotEmpty() && it.state.value != MoonlightSessionState.Idle }
                    .keys
        }

        private fun hostFor(hostId: String): MoonlightHost? =
            _connections.value[hostId]?.host?.value
                ?: store.get(hostId)?.toHost()
                ?: _discovered.value.firstOrNull { it.id == hostId }

        // Re-probe immediately before starting a session: the pairing is remembered trust
        // and the host may have dropped it, or come back as a different machine entirely,
        // since the last time anything asked.
        private suspend fun openStream(
            conn: MoonlightConnection,
            host: MoonlightHost,
        ) {
            conn.markLaunching()
            publishSessionHosts()
            val probe = probe(host)
            if (probe.trust != MoonlightTrustState.PAIRED) {
                // The binding screen re-probes and renders the same verdict, so the user
                // is told; the log line is what makes a bug report readable.
                Log.w(TAG, "not opening a session on ${host.address}: trust is ${probe.trust}")
                conn.markDisconnected()
                if (probe.trust == MoonlightTrustState.REPLACED) {
                    _events.emit(MoonlightConnectionEvent.HostReplaced(host))
                }
                return
            }
            val remembered = store.get(host.id)
            val appId = remembered?.lastAppId?.takeIf { it.isNotEmpty() } ?: probe.apps.firstOrNull()?.id
            if (appId == null) {
                conn.markDisconnected()
                _events.emit(MoonlightConnectionEvent.Error("No apps available on ${host.name}."))
                return
            }
            val appName =
                remembered
                    ?.lastAppName
                    .orEmpty()
                    .ifEmpty {
                        probe.apps
                            .firstOrNull { it.id == appId }
                            ?.title
                            .orEmpty()
                    }
            launchAndStream(conn, host, appId, appName)
        }

        /**
         * Pair with [host]: emits [MoonlightConnectionEvent.PairingPinReady] with
         * the generated PIN, runs the 5 phases, and returns true when paired.
         * Public so the binding screen can await pairing before fetching the app list.
         */
        suspend fun pairHost(host: MoonlightHost): Boolean =
            withContext(ioDispatcher) {
                Log.i(TAG, "pair requested for ${host.name} at ${host.address} (${host.id})")
                if (isPaired(host)) {
                    // CONFIRMING TRUST IS A PAIRING OUTCOME AND HAS TO PERSIST LIKE ONE.
                    // A device that forgot a host the host still trusts is answered here
                    // without a PIN. Emitting Paired and writing nothing left the record
                    // empty and the row reading "Not paired", so the button did the same
                    // nothing every time it was pressed, and the only trace of any of it
                    // was a mutual-TLS /serverinfo in the HOST's log.
                    Log.i(TAG, "${host.address} already trusts this device; recording the pairing")
                    rememberPaired(host, paired = true)
                    _events.emit(MoonlightConnectionEvent.Paired(host))
                    true
                } else {
                    pair(host)
                }
            }

        /** Fetch the host's app list (empty when unreachable/unpaired). */
        suspend fun fetchApps(host: MoonlightHost): List<MoonlightXml.App> = withContext(ioDispatcher) { fetchAppList(host) }

        private fun fetchAppList(host: MoonlightHost): List<MoonlightXml.App> {
            val reply = gateway.getHttps(MoonlightUrls.appList(host.address, host.httpsPort, deviceId), host.id)
            if (!reply.ok) throw java.io.IOException("applist refused by ${host.address}: HTTP ${reply.status}")
            return MoonlightXml.parseAppList(reply.body)
        }

        private fun isPaired(host: MoonlightHost): Boolean {
            val reply = gateway.getHttps(MoonlightUrls.serverInfoHttps(host.address, host.httpsPort, deviceId), host.id)
            if (!reply.ok) {
                Log.i(TAG, "${host.address} did not answer mutual TLS (HTTP ${reply.status}): a PIN is needed")
                return false
            }
            val paired = MoonlightXml.parseServerInfo(reply.body)?.paired == true
            Log.i(TAG, "${host.address} answered mutual TLS, PairStatus paired=$paired")
            if (paired) markVerified(host.id)
            return paired
        }

        /** Runs the 5-phase pairing; phase 1 blocks until the user enters the PIN. */
        @Suppress("ReturnCount") // each early return is a distinct phase-failure bail
        private suspend fun pair(host: MoonlightHost): Boolean {
            val pin = randomPin()
            Log.i(TAG, "pairing ${host.address}: PIN issued, phase 1 will wait up to ${PAIR_WAIT_S}s for it")
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
                val cert =
                    MoonlightXml.parsePairReply(p1.body)?.plainCert
                        ?: return pairingRefused(host, "phase 1 returned no host certificate (HTTP ${p1.status})")
                pairing.onPhase1(
                    String(
                        com.tinkernorth.dish.core.net
                            .hexToBytes(cert),
                        Charsets.US_ASCII,
                    ),
                )

                val p2 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase2Params(deviceId)))
                val challenge =
                    MoonlightXml.parsePairReply(p2.body)?.challengeResponse
                        ?: return pairingRefused(host, "phase 2 returned no challenge response")
                if (!pairing.onPhase2(challenge)) return pairingRefused(host, "phase 2 challenge did not verify (wrong PIN)")

                val p3 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase3Params(deviceId)))
                val secret =
                    MoonlightXml.parsePairReply(p3.body)?.pairingSecret
                        ?: return pairingRefused(host, "phase 3 returned no pairing secret")
                if (!pairing.onPhase3(secret)) return pairingRefused(host, "phase 3 signature did not verify")

                val p4 = gateway.getHttp(MoonlightUrls.pairHttp(host.address, host.httpPort, pairing.phase4Params(deviceId)))
                if (MoonlightXml.parsePairReply(p4.body)?.paired != true) {
                    return pairingRefused(host, "phase 4 did not confirm the pairing")
                }

                // Phases 1-4 proved the peer holds the PIN-derived key and signed with
                // the certificate it presented, which outranks the pin this would keep.
                // Without re-arming, a rebuilt host is refused with no way past it.
                gateway.forgetPin(host.id)

                // Phase 5 (HTTPS): confirm the client-cert-authenticated channel.
                gateway.getHttps(MoonlightUrls.pairHttps(host.address, host.httpsPort, pairing.phase5Params(deviceId)), host.id)
                Log.i(TAG, "paired with ${host.name} at ${host.address}")
                rememberPaired(host, paired = true)
                _events.emit(MoonlightConnectionEvent.Paired(host))
                true
            }.getOrElse { failure ->
                // A cancelled pairing is the user's own doing, not a refusal: letting
                // runCatching turn it into one would raise "the host did not accept the
                // PIN" the moment they pressed Cancel.
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.w(TAG, "pairing failed for ${host.address}: ${failure.message}", failure)
                pairingRefused(host, failure.message ?: failure.javaClass.simpleName)
            }
        }

        private suspend fun pairingRefused(
            host: MoonlightHost,
            reason: String,
        ): Boolean {
            Log.w(TAG, "pairing refused by ${host.address}: $reason")
            _events.emit(MoonlightConnectionEvent.PairingFailed(host, reason))
            return false
        }

        private suspend fun launchAndStream(
            conn: MoonlightConnection,
            host: MoonlightHost,
            appId: String,
            appName: String,
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
                giveUp(conn, host)
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
                giveUp(conn, host)
                return
            }
            val session =
                MoonlightControlSession(rikey, rtsp.enetConnectData, transport, System::currentTimeMillis) { event ->
                    if (event is com.tinkernorth.dish.core.net.moonlight.MoonlightEvent.Termination) onHostTerminated(conn, host)
                    conn.dispatchFeedback(event)
                }
            if (!session.connect()) {
                Log.w(TAG, "control channel refused on ${host.address}:${rtsp.controlPort}")
                giveUp(conn, host)
                return
            }
            val resolvedName = appName.ifEmpty { runCatching { appTitleFor(host, appId) }.getOrNull().orEmpty() }
            Log.i(TAG, "live on ${host.address}, control ${rtsp.controlPort}, ${conn.padCount} pad(s)")
            conn.markLive(session, appId, resolvedName)
            rememberPaired(host, appId, resolvedName, paired = true)
            publishSessionHosts()
        }

        private fun appTitleFor(
            host: MoonlightHost,
            appId: String,
        ): String? = fetchAppList(host).firstOrNull { it.id == appId }?.title

        // The host ended it, so there is nothing to rejoin: the pads stay claimed by
        // their bindings and the next use starts a new session rather than resuming.
        private fun onHostTerminated(
            conn: MoonlightConnection,
            host: MoonlightHost,
        ) {
            conn.markEnded()
            publishSessionHosts()
            scope.launch { _events.emit(MoonlightConnectionEvent.EndedByHost(host)) }
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
            conn.markDisconnected()
            _events.emit(MoonlightConnectionEvent.LaunchRefused(host, status?.message.orEmpty()))
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
                conn.markDisconnected()
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
            conn.markDisconnected()
            _events.emit(MoonlightConnectionEvent.RejoinRefused(host))
            return null
        }

        /**
         * Tell [host] to end the app it is running. The protocol's own way out of
         * "an app is already running", and the only one when the host will not
         * resume that session for us. /cancel answers 200 whether or not anything
         * was running, so the caller re-probes rather than believing it.
         */
        fun quitHostApp(host: MoonlightHost) {
            scope.launch(ioDispatcher) {
                _connections.value[host.id]?.let { conn ->
                    conn.pads.value.keys
                        .toList()
                        .forEach(conn::releasePad)
                    conn.markDisconnected()
                }
                cancelHostApp(host)
                publishSessionHosts()
                _events.emit(MoonlightConnectionEvent.Notice("Asked ${host.name} to close the app it is running."))
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
        ) {
            conn.markDisconnected()
            runCatching { cancelHostApp(host) }
            _events.emit(MoonlightConnectionEvent.SetupFailed(host))
        }

        fun disconnect(id: String) {
            _connections.value[id]?.markDisconnected()
            publishSessionHosts()
        }

        /**
         * Drop every trace of [id] this device holds: the session, the remembered
         * record, and THE PINNED HOST CERTIFICATE, which used to survive a forget and
         * refuse a host that had since rotated its own.
         *
         * FORGET IS UNILATERAL AND CANNOT BE ANYTHING ELSE. The protocol has no unpair
         * verb, so the host keeps its record of this device until a human removes it
         * there. The confirmation copy says so.
         */
        fun forget(id: String) {
            // Off the caller's thread because the /cancel below is a blocking mutual-TLS
            // call and this is reached straight from a row tap. The ORDER inside is what
            // makes it one step and not three: the cancel has to go before the pin does,
            // or the handshake it needs finds no pin, trusts the host on first use, and
            // writes a new one over the top of the forget.
            scope.launch(ioDispatcher) {
                val host = hostFor(id)
                Log.i(TAG, "forgetting ${host?.address ?: id}")
                releaseSessionFor(id, host)
                store.remove(id)
                gateway.forgetPin(id)
                _connections.updateAndGet { it - id }
                _discovered.value = _discovered.value.filterNot { it.id == id }
                _verifiedHostIds.value = _verifiedHostIds.value - id
                publishSessionHosts()
            }
        }

        private fun markVerified(hostId: String) {
            _verifiedHostIds.value = _verifiedHostIds.value + hostId
        }

        private fun releaseSessionFor(
            id: String,
            host: MoonlightHost?,
        ) {
            val conn = _connections.value[id] ?: return
            val live = conn.state.value == MoonlightSessionState.Live
            conn.pads.value.keys
                .toList()
                .forEach(conn::releasePad)
            conn.markDisconnected()
            if (live && host != null) runCatching { cancelHostApp(host) }
        }

        /** Remember which app the session settled on so the next binding can say it is joining it. */
        fun rememberApp(
            hostId: String,
            appId: String,
            appName: String,
        ) {
            val entry = store.get(hostId)
            if (entry == null) {
                // Dropping the pick here rendered the row as chosen and then started
                // something else, for every host the user had only discovered.
                val host = hostFor(hostId)
                if (host == null) {
                    Log.w(TAG, "app pick for unknown host $hostId discarded")
                    return
                }
                Log.i(TAG, "app pick $appId for $hostId on a host with no record yet; recording interest")
                rememberPaired(host, appId, appName, paired = false)
                return
            }
            Log.i(TAG, "app for $hostId settled on $appId ($appName)")
            store.put(entry.copy(lastAppId = appId, lastAppName = appName))
        }

        /**
         * Record a host the user has committed to without claiming it is paired.
         *
         * A host that lives only in the discovery list disappears the moment a browse
         * misses it, and a binding pointing at one loses its summary, its pads and its
         * session with it. Adding by address and binding are both durable intent, so
         * both land here; [RememberedMoonlight.paired] keeps interest and trust apart.
         */
        fun rememberInterest(host: MoonlightHost) {
            if (store.get(host.id) != null) return
            Log.i(TAG, "remembering ${host.name} at ${host.address} as ${host.id} (not paired)")
            rememberPaired(host, paired = false)
        }

        /** The same, for a host known only by id (the binding hub has no [MoonlightHost]). */
        fun rememberInterest(hostId: String) {
            val host = hostFor(hostId)
            if (host == null) {
                Log.w(TAG, "cannot remember unknown Moonlight host $hostId")
                return
            }
            rememberInterest(host)
        }

        private fun rememberPaired(
            host: MoonlightHost,
            appId: String = store.get(host.id)?.lastAppId.orEmpty(),
            appName: String = store.get(host.id)?.lastAppName.orEmpty(),
            paired: Boolean,
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
                    lastAppName = appName,
                    emulatedType = rememberedEmulatedType(host.id),
                    // Trust only ever climbs here: a launch on a host already paired
                    // must not demote it, and interest must not promote it.
                    paired = paired || store.get(host.id)?.paired == true,
                ),
            )
        }

        /** The remembered emulated-device pick for [hostId], defaulting to Auto. */
        fun rememberedEmulatedType(hostId: String): Int =
            MoonlightEmulatedType.fromStored(store.get(hostId)?.emulatedType ?: MoonlightEmulatedType.AUTO)

        /** The remembered last-launched app id for [hostId], or empty. */
        fun rememberedAppId(hostId: String): String = store.get(hostId)?.lastAppId.orEmpty()

        /** The remembered last-launched app title for [hostId], or empty. */
        fun rememberedAppName(hostId: String): String = store.get(hostId)?.lastAppName.orEmpty()

        fun rememberedHost(hostId: String): MoonlightHost? = hostFor(hostId)

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
            const val PAIR_WAIT_S = MoonlightHttpGateway.PAIR_PIN_TIMEOUT_MS / 1000
        }
    }
