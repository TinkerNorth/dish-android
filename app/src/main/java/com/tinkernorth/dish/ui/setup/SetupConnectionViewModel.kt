// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.satelliteLinkState
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.ConnectIntent
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 3 destination. Thin orchestration over SatelliteConnectionManager:
// 3A is a local path pick; 3B drives discovery + the manager's connect/auto-
// reconnect; 3C reuses PairPinDialog and the manager's pairWithPin/requestApproval.
// The host list is derived by combining the manager's discovered servers with
// its live connections + stale set, reusing the same FSM->LinkState mapping the
// dashboard composer uses so both screens agree by construction. Discovered
// servers carry the DiscoveredServer the pair/connect calls need; the composer's
// ConnectionSummary does not, which is why this maps from the manager directly.
@HiltViewModel
class SetupConnectionViewModel
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val hub: ConnectionCoordinator,
    ) : ViewModel() {
        enum class Step { PATH, SATELLITE }

        data class Host(
            val id: String,
            val name: String,
            val link: LinkState,
            val server: DiscoveredServer,
        )

        data class State(
            val step: Step = Step.PATH,
            val scanning: Boolean = false,
            val hosts: List<Host> = emptyList(),
        )

        sealed interface Event {
            // 3C: the tapped host has no pairing key; open the reused PIN dialog.
            data class ShowPairing(
                val server: DiscoveredServer,
            ) : Event

            // A satellite reached Connected/Unstable: hand off to the configure step.
            data class Connected(
                val hostId: String,
            ) : Event

            data class Error(
                val message: String,
            ) : Event
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
        val events: SharedFlow<Event> = _events.asSharedFlow()

        // Hosts we've kicked an auto-reconnect for, so a remembered host that
        // reappears mid-scan only fires one silent reconnect (the row then reads
        // "Reconnecting" off its Connecting LinkState).
        private val autoReconnected = mutableSetOf<String>()

        // The host the user is actively pairing/connecting, so the Connected
        // hand-off fires once for their tap and not for every background session.
        private var pendingHostId: String? = null

        init {
            // Live link state must come from the coordinator: it flattens each
            // session's nested state flow, whereas satellite.connections is a map
            // whose identity doesn't change when a session goes Connecting -> Live,
            // so combining on it alone never observes a pairing actually complete.
            combine(
                satellite.discoveredServers,
                hub.connections,
                satellite.connections,
                satellite.staleSatelliteIds,
            ) { discovered, summaries, conns, stale ->
                buildHosts(discovered, summaries, conns, stale)
            }.onEach { hosts -> onHosts(hosts) }.launchIn(viewModelScope)

            satellite.isScanning
                .onEach { scanning -> _state.update { it.copy(scanning = scanning) } }
                .launchIn(viewModelScope)

            satellite.events
                .onEach { onConnectionEvent(it) }
                .launchIn(viewModelScope)
        }

        // 3A: Satellite path stays on this screen and starts discovery; the
        // Activity routes the Bluetooth-host pick to its own branch.
        fun chooseSatellite() {
            if (_state.value.step == Step.SATELLITE) return
            _state.update { it.copy(step = Step.SATELLITE) }
            startDiscovery()
        }

        fun startDiscovery() = satellite.startDiscovery()

        // 3B/3C: a paired/ready host connects; an unpaired one promotes to the
        // PIN dialog. A live host short-circuits straight to the hand-off.
        fun onHostTapped(id: String) {
            val host = _state.value.hosts.firstOrNull { it.id == id } ?: return
            pendingHostId = id
            when (host.link) {
                LinkState.Connected, LinkState.Unstable -> emit(Event.Connected(id))
                LinkState.Stale -> emit(Event.ShowPairing(host.server))
                else -> satellite.connect(host.server, ConnectIntent.USER_INITIATED)
            }
        }

        fun pairWithPin(
            server: DiscoveredServer,
            pin: String,
        ) {
            pendingHostId = SatelliteConnection.idFor(server)
            satellite.pairWithPin(server, pin)
        }

        fun requestApproval(
            server: DiscoveredServer,
            clientPin: String,
        ) {
            pendingHostId = SatelliteConnection.idFor(server)
            satellite.requestApproval(server, clientPin)
        }

        // Back from the satellite list rewinds to the path pick; the path pick
        // itself is the screen's root (Activity finishes).
        fun back(): Boolean =
            when (_state.value.step) {
                Step.SATELLITE -> {
                    _state.update { it.copy(step = Step.PATH) }
                    true
                }
                Step.PATH -> false
            }

        private fun onHosts(hosts: List<Host>) {
            _state.update { it.copy(hosts = hosts) }
            // A remembered host reappearing on Wi-Fi reconnects itself once; the
            // manager skips the pair handshake when it already holds the key.
            hosts.forEach { host ->
                if (host.link == LinkState.Saved && host.id !in autoReconnected) {
                    autoReconnected += host.id
                    satellite.connect(host.server, ConnectIntent.AUTO_RECONNECT)
                }
            }
            // Promote to configure only for the host the user actually drove
            // (tapped or paired -> pendingHostId). A background auto-reconnect
            // going live on its own must not yank the user forward, and
            // re-entering with a satellite already connected must not skip the
            // picker entirely.
            val target = hosts.firstOrNull { it.id == pendingHostId && it.link.isLive() }
            if (target != null) {
                pendingHostId = null
                emit(Event.Connected(target.id))
            }
        }

        private fun onConnectionEvent(event: ConnectionEvent) {
            when (event) {
                is ConnectionEvent.PairingRequired -> emit(Event.ShowPairing(event.server))
                is ConnectionEvent.Error -> emit(Event.Error(event.message))
            }
        }

        private fun buildHosts(
            discovered: List<DiscoveredServer>,
            summaries: List<ConnectionSummary>,
            conns: Map<String, SatelliteConnection>,
            stale: Set<String>,
        ): List<Host> {
            val discoveredById = discovered.associateBy { SatelliteConnection.idFor(it) }
            // The coordinator's summary carries the reactive LinkState; prefer it,
            // and fall back to a computed state for a freshly discovered host the
            // coordinator hasn't surfaced yet.
            val liveById = summaries.filter { it.kind == ConnectionKind.SATELLITE }.associate { it.id to it.live }
            val ids = discoveredById.keys + liveById.keys + conns.keys
            return ids.mapNotNull { id ->
                val server = conns[id]?.server?.value ?: discoveredById[id] ?: return@mapNotNull null
                Host(
                    id = id,
                    name = server.name,
                    link =
                        liveById[id] ?: satelliteLinkState(
                            state = conns[id]?.state?.value,
                            isStale = id in stale,
                            isDiscovered = id in discoveredById,
                        ),
                    server = server,
                )
            }
        }

        private fun emit(event: Event) {
            viewModelScope.launch { _events.emit(event) }
        }

        private fun LinkState.isLive(): Boolean = this == LinkState.Connected || this == LinkState.Unstable
    }
