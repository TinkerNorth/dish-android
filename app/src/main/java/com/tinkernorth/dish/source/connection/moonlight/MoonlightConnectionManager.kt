// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.BITS_READ_AT_ARRIVAL
import com.tinkernorth.dish.core.net.moonlight.MoonlightApp
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.MoonlightPairing
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.core.net.moonlight.ServerInfo
import com.tinkernorth.dish.core.net.moonlight.Status
import com.tinkernorth.dish.core.net.moonlight.appList
import com.tinkernorth.dish.core.net.moonlight.cancel
import com.tinkernorth.dish.core.net.moonlight.foldedRecords
import com.tinkernorth.dish.core.net.moonlight.fromStored
import com.tinkernorth.dish.core.net.moonlight.launch
import com.tinkernorth.dish.core.net.moonlight.moonlightHostIdFor
import com.tinkernorth.dish.core.net.moonlight.pairHttp
import com.tinkernorth.dish.core.net.moonlight.pairHttps
import com.tinkernorth.dish.core.net.moonlight.parseAppList
import com.tinkernorth.dish.core.net.moonlight.parseMoonlightCert
import com.tinkernorth.dish.core.net.moonlight.parsePairReply
import com.tinkernorth.dish.core.net.moonlight.parseServerInfo
import com.tinkernorth.dish.core.net.moonlight.parseStatus
import com.tinkernorth.dish.core.net.moonlight.randomBytes
import com.tinkernorth.dish.core.net.moonlight.resume
import com.tinkernorth.dish.core.net.moonlight.serverInfoHttp
import com.tinkernorth.dish.core.net.moonlight.serverInfoHttps
import com.tinkernorth.dish.core.net.moonlight.trustedOf
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
import com.tinkernorth.dish.source.store.MoonlightHostFactsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed class MoonlightConnectionEvent {
    /** The dish generated [pin]; the user must type it into the host's web UI. */
    data class PairingPinReady(
        val host: MoonlightHost,
        val pin: String,
    ) : MoonlightConnectionEvent()

    data class Error(
        val error: MoonlightError,
    ) : MoonlightConnectionEvent()

    /** The dish asked [host] to close the app it is running; the host does not say whether it will. */
    data class AppCloseRequested(
        val host: MoonlightHost,
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

/** Why a Moonlight request failed, as a kind the UI words in the user's language. */
sealed interface MoonlightError {
    data class NoHostAnswered(
        val address: String,
    ) : MoonlightError

    data class NoAppsAvailable(
        val hostName: String,
    ) : MoonlightError
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

/** What converging one requested pad does with the pad its slot already holds on the session. */
enum class PadPlacement { ACQUIRE, KEEP, REANNOUNCE }

// A held pad is re-announced only when the host would build another pad for the request: another
// type, or a change in a bit it reads at arrival. A replug unplugs the pad in the game, so a
// change in bits the host never reads keeps the pad as it is (and as the dashboard shows it).
internal fun padPlacement(
    held: MoonlightPad?,
    wanted: MoonlightPadRequest,
): PadPlacement =
    when {
        held == null -> PadPlacement.ACQUIRE
        held.emulatedType != wanted.emulatedType -> PadPlacement.REANNOUNCE
        (held.capabilities xor wanted.capabilities) and BITS_READ_AT_ARRIVAL != 0 -> PadPlacement.REANNOUNCE
        else -> PadPlacement.KEEP
    }

/** One binding's claim on a host session: which slot, and what pad to announce for it. */
data class MoonlightPadRequest(
    val slotId: String,
    val emulatedType: Int,
    val capabilities: Int,
    val supportedButtons: Int,
)

// What a host says about itself in a /serverinfo reply, when it answered one.
private fun serverInfoIn(reply: MoonlightHttpGateway.Reply): ServerInfo? = reply.takeIf { it.ok }?.let { parseServerInfo(it.body) }

// A phase reply as the host gave it: its status line, and its own words when it had any.
private fun hostSaid(reply: MoonlightHttpGateway.Reply): String {
    val words = parseStatus(reply.body)?.message.orEmpty()
    return if (words.isBlank()) "HTTP ${reply.status}" else "HTTP ${reply.status}: $words"
}

// A host that answers with no hostname is shown by the address that was typed, which is
// the only name the user has for it.
private fun manualHostFrom(
    address: String,
    info: ServerInfo,
) = MoonlightHost(
    name = info.hostname.ifEmpty { address },
    address = address,
    httpPort = externalPortOr(info),
    httpsPort = info.httpsPort ?: MoonlightHost.DEFAULT_HTTPS_PORT,
    uniqueId = info.uniqueId,
    manual = true,
)

private fun externalPortOr(info: ServerInfo): Int = info.externalPort ?: MoonlightHost.DEFAULT_HTTP_PORT

// The /launch response carries sessionUrl0 = rtsp://ip:port; pull the port.
private fun parseRtspPort(xml: String): Int? =
    Regex("rtsp://[^:<]+:(\\d+)")
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

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
        private val hostFacts: MoonlightHostFactsStore = MoonlightHostFactsStore(),
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
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val events: SharedFlow<MoonlightConnectionEvent> = _events.asSharedFlow()

        val remembered: StateFlow<List<RememberedMoonlight>> get() = store.entries

        private val deviceId by lazy { getOrCreateUniqueId() }

        // Serialises the whole converge so two emissions cannot both decide they are
        // the first pad on a host and launch it twice. A forget takes it too.
        private val convergeLock = Mutex()

        // Bumped by forget, per host. A probe notes the one it started under and writes nothing once
        // it has moved: whatever the host answers then was on the wire when it was forgotten.
        private val epochs = ConcurrentHashMap<String, Int>()

        @Volatile private var desired: Map<String, List<MoonlightPadRequest>> = emptyMap()

        init {
            fileHostsUnderTheirAddresses()
        }

        fun get(id: String): MoonlightConnection? = _connections.value[id]

        // An earlier version filed a host under its uniqueid once it knew it, which named one machine
        // twice. Each such record moves to the address, with the pin of the record whose trust is kept;
        // at start-up, before any binding exists to point at the old name.
        private fun fileHostsUnderTheirAddresses() {
            for (record in store.all()) {
                val addressId = moonlightHostIdFor(record.address)
                if (record.id == addressId) continue
                Log.i(TAG, "filing ${record.id} under $addressId")
                val filed = store.get(addressId)
                val trustedId = filed?.let { trustedOf(it, record).id } ?: record.id
                val pinsAgree = gateway.foldPins(record.id, addressId, trustedHostId = trustedId)
                store.put(foldedRecords(filed = filed, refiled = record.copy(id = addressId), pinsAgree = pinsAgree))
                store.remove(record.id)
            }
        }

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
                val info = probeServerInfo(address)
                if (info == null) {
                    Log.w(TAG, "manual add: nothing answered /serverinfo at $address")
                    _events.emit(MoonlightConnectionEvent.Error(MoonlightError.NoHostAnswered(address)))
                    return@launch
                }
                val host = manualHostFrom(address, info)
                Log.i(TAG, "manual add: ${host.name} at $address as ${host.id}")
                _discovered.mergeHost(host)
                // Typing an address is durable interest, so the host outlives the discovery list
                // it would otherwise be the only copy of.
                rememberInterest(host)
                keepFirstUniqueId(host.id, info)
            }
        }

        // The first uniqueid a remembered host answers with is kept: it is the witness that later
        // tells the machine this client paired with from another one behind the same address.
        private fun keepFirstUniqueId(
            hostId: String,
            info: ServerInfo,
        ) {
            val record = store.get(hostId) ?: return
            val isTheFirstAnswer = record.uniqueId.isEmpty() && info.uniqueId.isNotEmpty()
            if (isTheFirstAnswer) store.put(record.copy(uniqueId = info.uniqueId))
        }

        // The host's app list is its own word on what it can start. A pick it no longer lists would be
        // refused on every attempt, behind a refusal that hides the picker it could be changed in, so it
        // is forgotten, and the host's first app starts, as the card promises for a host with no pick.
        private fun forgetAPickTheHostDropped(
            hostId: String,
            listed: List<MoonlightApp>,
        ) {
            val record = store.get(hostId) ?: return
            val pick = record.lastAppId
            val theHostDroppedIt = pick.isNotEmpty() && listed.none { it.id == pick }
            if (!theHostDroppedIt) return
            Log.i(TAG, "$hostId no longer lists app $pick; forgetting it as the pick")
            store.put(record.copy(lastAppId = "", lastAppName = ""))
        }

        // Plain HTTP answers any caller, paired or not, and names the machine behind the address.
        private fun plainServerInfo(host: MoonlightHost): ServerInfo? =
            serverInfoIn(gateway.getHttp(serverInfoHttp(host.address, host.httpPort, deviceId)))

        // Plain HTTP on the default port: a host that has never been paired will not talk HTTPS
        // to this client yet, and the ports it really listens on come back in the answer.
        private suspend fun probeServerInfo(address: String): ServerInfo? =
            serverInfoIn(gateway.getHttp(serverInfoHttp(address, MoonlightHost.DEFAULT_HTTP_PORT, deviceId)))

        private fun MutableStateFlow<List<MoonlightHost>>.mergeHost(host: MoonlightHost) {
            value = value.filterNot { it.id == host.id } + host
        }

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
                val epoch = epochOf(host.id)
                val plain = plainServerInfo(host)
                if (epochOf(host.id) != epoch) return@withContext FORGOTTEN
                plain?.let { hostFacts.note(host.id, it) }
                plain?.let { keepFirstUniqueId(host.id, it) }
                // Holding a pairing is the paired flag, never a non-empty uniqueid: real hosts
                // publish no uniqueid TXT record at all.
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
                probeOverMutualTls(host, holdsAPairing = record != null, epoch)
            }

        // The half of [probe] only a paired device is answered on, and the only proof a pairing still
        // stands. It stops, writing nothing, once the host is forgotten since [epoch].
        private fun probeOverMutualTls(
            host: MoonlightHost,
            holdsAPairing: Boolean,
            epoch: Int,
        ): MoonlightProbe {
            // The plaintext PairStatus is not an answer about pairing: Sunshine computes it only
            // on the mutual-TLS route and hands every plaintext caller a 0.
            val secure = gateway.getHttps(serverInfoHttps(host.address, host.httpsPort, deviceId), host.id)
            if (epochOf(host.id) != epoch) return FORGOTTEN
            val untrusted = if (holdsAPairing) MoonlightTrustState.TRUST_LOST else MoonlightTrustState.NOT_PAIRED
            if (!secure.ok) {
                Log.i(TAG, "${host.address} refused mutual TLS (HTTP ${secure.status}): $untrusted")
                return MoonlightProbe(trust = untrusted)
            }
            val info = parseServerInfo(secure.body)
            info?.let { hostFacts.note(host.id, it) }
            if (info?.paired != true) {
                Log.i(TAG, "${host.address} answered mutual TLS unpaired: $untrusted")
                return MoonlightProbe(trust = untrusted)
            }
            val apps = runCatching { fetchAppList(host) }.getOrNull()
            if (epochOf(host.id) != epoch) return FORGOTTEN
            apps?.let { forgetAPickTheHostDropped(host.id, it) }
            markVerified(host.id)
            return MoonlightProbe(
                trust = MoonlightTrustState.PAIRED,
                apps = apps.orEmpty(),
                appsFetched = apps != null,
                appsFailed = apps == null,
                ownSession = info.currentGame != 0,
                currentAppId = info.currentGame.takeIf { it != 0 }?.toString(),
            )
        }

        private fun epochOf(hostId: String): Int = epochs[hostId] ?: 0

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

        // A pad that found no room is the user's to hear about: the host already carries four.
        private suspend fun announcePads(
            conn: MoonlightConnection,
            host: MoonlightHost,
            pads: Collection<MoonlightPadRequest>,
        ) {
            for (pad in placePads(conn, pads)) _events.emit(MoonlightConnectionEvent.HostFull(host))
        }

        // Before the stream opens nothing is on the wire yet: markLive announces what is placed.
        private fun seedPads(
            conn: MoonlightConnection,
            pads: Collection<MoonlightPadRequest>,
        ) {
            placePads(conn, pads)
        }

        // Places every requested pad on the session and answers the ones that found no room. A
        // held pad the binding now asks for as another pad (another type, or motion bits it was
        // not announced with) is re-announced (on a live stream the host replugs it; before, only
        // the table changes), so a re-pick or a late gyro takes effect without an unbind.
        private fun placePads(
            conn: MoonlightConnection,
            pads: Collection<MoonlightPadRequest>,
        ): List<MoonlightPadRequest> {
            val unplaced = mutableListOf<MoonlightPadRequest>()
            for (pad in pads) {
                when (padPlacement(conn.padFor(pad.slotId), pad)) {
                    PadPlacement.ACQUIRE -> {
                        val acquired = conn.acquirePad(pad.slotId, pad.emulatedType, pad.capabilities, pad.supportedButtons)
                        if (acquired == null) unplaced += pad
                    }
                    PadPlacement.REANNOUNCE -> conn.reannouncePad(pad.slotId, pad.emulatedType, pad.capabilities, pad.supportedButtons)
                    PadPlacement.KEEP -> Unit
                }
            }
            return unplaced
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
                _events.emit(MoonlightConnectionEvent.Error(MoonlightError.NoAppsAvailable(host.name)))
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
                // A host that does not answer holds these questions to their whole budget. A Cancel hangs
                // them up and ends the pairing before it records or shows anything.
                val check = hangingUpOnCancel { line -> checkTrust(host, line) }
                if (check.trusted) {
                    // Confirming trust is a pairing outcome and persists like one: a device that forgot
                    // a host the host still trusts is answered here without a PIN.
                    Log.i(TAG, "${host.address} already trusts this device; recording the pairing")
                    rememberPaired(check.answering, paired = true, answeredUniqueId = check.answering.uniqueId)
                    _events.emit(MoonlightConnectionEvent.Paired(check.answering))
                    true
                } else {
                    pair(check.answering)
                }
            }

        // Who answers at a host's address as a pairing starts, and whether that machine already trusts this device.
        private class TrustCheck(
            val answering: MoonlightHost,
            val trusted: Boolean,
        )

        private fun checkTrust(
            host: MoonlightHost,
            line: CallLine,
        ): TrustCheck {
            val answering = answeringNow(host, line)
            return TrustCheck(answering, isPaired(answering, line))
        }

        // The host with the uniqueid it answers with now, which is the machine a pairing proves,
        // whoever answered at this address before; with the one remembered, when it does not answer.
        // The caller's copy may have been read before the last pairing named another machine.
        private fun answeringNow(
            host: MoonlightHost,
            line: CallLine,
        ): MoonlightHost {
            val answer = gateway.getHttpOn(line, serverInfoHttp(host.address, host.httpPort, deviceId))
            val answered = serverInfoIn(answer)?.uniqueId.orEmpty()
            val remembered = store.get(host.id)?.uniqueId.orEmpty()
            return host.copy(uniqueId = answered.ifEmpty { remembered })
        }

        /** Fetch the host's app list (empty when unreachable/unpaired). */
        suspend fun fetchApps(host: MoonlightHost): List<MoonlightApp> = withContext(ioDispatcher) { fetchAppList(host) }

        private fun fetchAppList(host: MoonlightHost): List<MoonlightApp> {
            val reply = gateway.getHttps(appList(host.address, host.httpsPort, deviceId), host.id)
            // A refusal, in the status line or in the body, lists nothing, and neither does a reply that
            // does not parse: none of them says anything about what the host can start.
            val status = parseStatus(reply.body)
            val isAList = reply.ok && status?.ok == true
            val hostStatus = status?.code?.toString() ?: "unreadable"
            if (!isAList) throw java.io.IOException("no app list from ${host.address}: HTTP ${reply.status}, host $hostStatus")
            return parseAppList(reply.body)
        }

        private fun isPaired(
            host: MoonlightHost,
            line: CallLine,
        ): Boolean {
            val reply = gateway.getHttpsOn(line, serverInfoHttps(host.address, host.httpsPort, deviceId), host.id)
            if (!reply.ok) {
                Log.i(TAG, "${host.address} did not answer mutual TLS (HTTP ${reply.status}): a PIN is needed")
                return false
            }
            val paired = parseServerInfo(reply.body)?.paired == true
            Log.i(TAG, "${host.address} answered mutual TLS, PairStatus paired=$paired")
            if (paired) markVerified(host.id)
            return paired
        }

        // The host stopped a pairing phase short: a missing field or a challenge that did not
        // verify. Its message is the reason the user sees.
        private class PairingRefused(
            reason: String,
        ) : Exception(reason)

        /**
         * Runs the 5-phase pairing; phase 1 blocks until the user enters the PIN. Nothing is written on
         * this side, the pin included, until phase 5 has confirmed it, so a Cancel, which hangs up
         * whichever phase is on the line, leaves this side as it was. The host keeps its half: Wolf
         * still takes a PIN typed later and refuses the next phase 1 once as out of order, and Sunshine
         * holds the pending pairing a while and refuses a new one until it lapses.
         */
        private suspend fun pair(host: MoonlightHost): Boolean {
            val pin = randomPin()
            Log.i(TAG, "pairing ${host.address}: PIN issued, phase 1 will wait up to ${PAIR_WAIT_S}s for it")
            _events.emit(MoonlightConnectionEvent.PairingPinReady(host, pin))
            val pairing = MoonlightPairing(identity, pin)
            val phases = runCatching { hangingUpOnCancel { line -> runPairingPhases(host, pairing, line) } }
            // A cancelled pairing is the user's own doing, not a refusal, and ends here with nothing
            // written: the hung-up phase would otherwise read as "the host did not accept the PIN".
            currentCoroutineContext().ensureActive()
            return phases.fold(
                onSuccess = { proven ->
                    Log.i(TAG, "paired with ${host.name} at ${host.address}")
                    gateway.pinProven(host.id, proven)
                    rememberPaired(host, paired = true, answeredUniqueId = host.uniqueId)
                    _events.emit(MoonlightConnectionEvent.Paired(host))
                    true
                },
                onFailure = { failure ->
                    if (failure !is PairingRefused) Log.w(TAG, "pairing failed for ${host.address}: ${failure.message}", failure)
                    pairingRefused(host, failure.message ?: failure.javaClass.simpleName)
                },
            )
        }

        // Phases 1 to 5 in order, on one line; any phase the host cuts short throws PairingRefused.
        // Hands back the host certificate the pairing proved.
        private fun runPairingPhases(
            host: MoonlightHost,
            pairing: MoonlightPairing,
            line: CallLine,
        ): X509Certificate {
            // Phase 1 (HTTP): the host prompts for the PIN and blocks until
            // entered, so this one waits on a human rather than on the network.
            val p1 =
                gateway.getHttpOn(
                    line,
                    pairHttp(host.address, host.httpPort, pairing.phase1Params(deviceId)),
                    MoonlightHttpGateway.PAIR_PIN_TIMEOUT_MS,
                )
            val cert =
                required(parsePairReply(p1.body)?.plainCert) { "phase 1 returned no host certificate (${hostSaid(p1)})" }
            val certificatePem = String(hexToBytes(cert), Charsets.US_ASCII)
            pairing.onPhase1(certificatePem)

            val p2 = gateway.getHttpOn(line, pairHttp(host.address, host.httpPort, pairing.phase2Params(deviceId)))
            val challenge =
                required(parsePairReply(p2.body)?.challengeResponse) { "phase 2 returned no challenge response (${hostSaid(p2)})" }
            verified(pairing.onPhase2(challenge)) { "phase 2 challenge did not verify (wrong PIN)" }

            val p3 = gateway.getHttpOn(line, pairHttp(host.address, host.httpPort, pairing.phase3Params(deviceId)))
            val secret =
                required(parsePairReply(p3.body)?.pairingSecret) { "phase 3 returned no pairing secret (${hostSaid(p3)})" }
            verified(pairing.onPhase3(secret)) { "phase 3 signature did not verify" }

            val p4 = gateway.getHttpOn(line, pairHttp(host.address, host.httpPort, pairing.phase4Params(deviceId)))
            verified(parsePairReply(p4.body)?.paired == true) { "phase 4 did not confirm the pairing (${hostSaid(p4)})" }

            // Phases 1-4 proved the peer holds the PIN-derived key and signed with the certificate it
            // presented. Phase 5 (HTTPS) trusts that certificate and no other, whatever is pinned: the
            // pin of a host since rebuilt would refuse it, and any other certificate is not the host
            // that paired.
            val proven = parseMoonlightCert(certificatePem)
            val p5 = gateway.getHttpsTrustingOn(line, pairHttps(host.address, host.httpsPort, pairing.phase5Params(deviceId)), proven)
            verified(parsePairReply(p5.body)?.paired == true) { "phase 5 did not confirm the pairing over mutual TLS (${hostSaid(p5)})" }
            return proven
        }

        // A phase's answer that must be there; the host refusing to give it ends the pairing.
        private fun <T : Any> required(
            value: T?,
            reason: () -> String,
        ): T = value ?: throw PairingRefused(reason())

        // A phase's check that must hold; the host failing it ends the pairing.
        private fun verified(
            holds: Boolean,
            reason: () -> String,
        ) {
            if (!holds) throw PairingRefused(reason())
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
            val rikey = randomBytes(RIKEY_LEN)
            val rikeyId =
                randomBytes(4).let {
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
                    onControlEvent(conn, host, event)
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
            val url = launch(host.address, host.httpsPort, deviceId, appId, rikeyHex, rikeyId, LAUNCH_MODE)
            val reply = gateway.getHttps(url, host.id)
            val status = parseStatus(reply.body)
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
            launchStatus: Status,
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
                gateway.getHttps(resume(host.address, host.httpsPort, deviceId, rikeyHex, rikeyId), host.id)
            val status = parseStatus(reply.body)
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
                _events.emit(MoonlightConnectionEvent.AppCloseRequested(host))
            }
        }

        private fun cancelHostApp(host: MoonlightHost): Boolean {
            val reply = gateway.getHttps(cancel(host.address, host.httpsPort, deviceId), host.id)
            val status = parseStatus(reply.body)
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
            //
            // Under the converge lock, so a session coming up on this host finishes first and is then
            // taken down with the rest: running beside it, the session wrote the host back once live.
            scope.launch(ioDispatcher) {
                convergeLock.withLock {
                    epochs.merge(id, 1, Int::plus)
                    val host = hostFor(id)
                    Log.i(TAG, "forgetting ${host?.address ?: id}")
                    releaseSessionFor(id, host)
                    store.remove(id)
                    gateway.forgetPin(id)
                    hostFacts.forget(id)
                    _connections.updateAndGet { it - id }
                    _discovered.value = _discovered.value.filterNot { it.id == id }
                    _verifiedHostIds.value = _verifiedHostIds.value - id
                    publishSessionHosts()
                }
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

        /**
         * Write [host]'s record. Only a pairing names the machine, with [answeredUniqueId]: the one
         * the host answered as when the pairing began. Every other write passes none and keeps the
         * machine remembered, since a host held from before the last pairing still names the one before.
         */
        private fun rememberPaired(
            host: MoonlightHost,
            appId: String = store.get(host.id)?.lastAppId.orEmpty(),
            appName: String = store.get(host.id)?.lastAppName.orEmpty(),
            paired: Boolean,
            answeredUniqueId: String = "",
        ) {
            val known = store.get(host.id)
            store.put(
                RememberedMoonlight(
                    id = host.id,
                    name = host.name,
                    address = host.address,
                    httpPort = host.httpPort,
                    httpsPort = host.httpsPort,
                    uniqueId = answeredUniqueId.ifEmpty { known?.uniqueId.orEmpty() },
                    lastAppId = appId,
                    lastAppName = appName,
                    emulatedType = rememberedEmulatedType(host.id),
                    // Trust only ever climbs here: a launch on a host already paired
                    // must not demote it, and interest must not promote it.
                    paired = paired || known?.paired == true,
                ),
            )
        }

        /** The remembered emulated-device pick for [hostId], defaulting to Auto. */
        fun rememberedEmulatedType(hostId: String): Int = fromStored(store.get(hostId)?.emulatedType ?: AUTO)

        /** The remembered last-launched app id for [hostId], or empty. */
        fun rememberedAppId(hostId: String): String = store.get(hostId)?.lastAppId.orEmpty()

        /** The remembered last-launched app title for [hostId], or empty. */
        fun rememberedAppName(hostId: String): String = store.get(hostId)?.lastAppName.orEmpty()

        fun rememberedHost(hostId: String): MoonlightHost? = hostFor(hostId)

        private fun randomPin(): String {
            val n = java.security.SecureRandom().nextInt(PIN_RANGE)
            return "%04d".format(n)
        }

        // A termination is the host's goodbye; everything else is feedback for the pad it names.
        private fun onControlEvent(
            conn: MoonlightConnection,
            host: MoonlightHost,
            event: MoonlightEvent,
        ) {
            if (event is MoonlightEvent.Termination) onHostTerminated(conn, host)
            conn.dispatchFeedback(event)
        }

        private fun getOrCreateUniqueId(): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return prefs.getString(KEY_UNIQUE_ID, null) ?: java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(UNIQUE_ID_LEN)
                .also { id -> prefs.edit { putString(KEY_UNIQUE_ID, id) } }
        }

        private companion object {
            const val TAG = "MoonlightConnectionMgr"
            const val PREFS_NAME = "moonlight"
            const val KEY_UNIQUE_ID = "uniqueid"
            const val UNIQUE_ID_LEN = 16
            const val EVENT_BUFFER = 8
            const val DISCOVERY_TIMEOUT_MS = 4000
            const val RIKEY_LEN = 16
            const val PIN_RANGE = 10_000
            const val LAUNCH_MODE = "1280x720x30"
            const val LAUNCH_WIDTH = 1280
            const val LAUNCH_HEIGHT = 720
            const val LAUNCH_FPS = 30
            const val BODY_LOG_CHARS = 256
            const val PAIR_WAIT_S = MoonlightHttpGateway.PAIR_PIN_TIMEOUT_MS / 1000

            // What a probe of a host forgotten while it was being asked has to say: nothing is held for it.
            val FORGOTTEN = MoonlightProbe(trust = MoonlightTrustState.NOT_PAIRED)
        }
    }
