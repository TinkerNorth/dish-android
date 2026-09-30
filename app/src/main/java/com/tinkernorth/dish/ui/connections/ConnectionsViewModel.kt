// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionEvent
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel
    @Inject
    constructor(
        hub: ConnectionCoordinator,
        satellite: SatelliteConnectionManager,
        private val moonlight: MoonlightConnectionManager,
        store: ConnectionStore,
        hostFeatures: SatelliteHostFeaturesStore,
    ) : ViewModel() {
        // The satellite/BT half of the state, so the Moonlight flows fit in one more combine.
        private data class SatBtSlice(
            val discovered: List<com.tinkernorth.dish.core.model.DiscoveredServer>,
            val bluetoothSummaries: List<com.tinkernorth.dish.composer.ConnectionSummary>,
            val rememberedBtIds: Set<String>,
            val scanning: Boolean,
            val lastScanAtMs: Long?,
        )

        private val satBt =
            combine(
                hub.connections,
                satellite.discoveredServers,
                satellite.isScanning,
                satellite.lastScanAtMs,
                store.rememberedBtFlow,
            ) { conns, discovered, scanning, lastScan, rememberedBt ->
                SatBtSlice(
                    discovered = discovered,
                    bluetoothSummaries = bluetoothSummaries(conns),
                    rememberedBtIds = rememberedBt.mapTo(mutableSetOf()) { it.id },
                    scanning = scanning,
                    lastScanAtMs = lastScan,
                )
            }

        // The Moonlight half, folded so the whole state still fits one combine. Only a record
        // that says paired counts as trust; the list also carries hosts the user merely added
        // or bound to.
        private data class MoonlightSlice(
            val discovered: List<com.tinkernorth.dish.core.net.moonlight.MoonlightHost>,
            val scanning: Boolean,
            val pairedIds: Set<String>,
            val verifiedIds: Set<String>,
        )

        private val moonlightSlice =
            combine(
                moonlight.discovered,
                moonlight.isScanning,
                moonlight.remembered,
                moonlight.verifiedHostIds,
            ) { discovered, scanning, remembered, verified ->
                MoonlightSlice(
                    discovered = discovered,
                    scanning = scanning,
                    pairedIds = remembered.filter { it.paired }.mapTo(mutableSetOf()) { it.id },
                    verifiedIds = verified,
                )
            }

        val ui: StateFlow<ConnectionsUiState> =
            combine(
                satBt,
                hub.connections,
                moonlightSlice,
                hostFeatures.state,
            ) { slice, conns, ml, features ->
                ConnectionsUiState(
                    satelliteRows = satelliteRows(conns, slice.discovered, features),
                    bluetoothSummaries = slice.bluetoothSummaries,
                    moonlightRows = moonlightRows(conns, ml.discovered, ml.pairedIds, ml.verifiedIds),
                    rememberedBtIds = slice.rememberedBtIds,
                    scanning = slice.scanning,
                    moonlightScanning = ml.scanning,
                    lastScanAtMs = slice.lastScanAtMs,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ConnectionsUiState.Empty)

        private val _moonlightPin = MutableStateFlow<MoonlightPinPrompt?>(null)

        /** The PIN of the pairing this screen started, for as long as that pairing runs. */
        val moonlightPin: StateFlow<MoonlightPinPrompt?> = _moonlightPin.asStateFlow()

        private var moonlightPairingJob: Job? = null

        /**
         * Pair [host], in place of any pairing this screen had running. The pairing and its PIN belong
         * here rather than to the screen, so a screen recreated mid-pairing, by a rotation, finds both.
         */
        fun pairMoonlight(host: MoonlightHost) {
            moonlightPairingJob?.cancel()
            moonlightPairingJob = viewModelScope.launch { pairShowingItsPin(host) }
        }

        /** Cancel the pairing this screen started, so phase 1 does not hold its socket for the PIN window. */
        fun cancelMoonlightPairing() {
            moonlightPairingJob?.cancel()
            moonlightPairingJob = null
            _moonlightPin.value = null
        }

        // The PIN the manager announces for this pairing is shown until the pairing ends. A pairing
        // replaced by another leaves the PIN alone: it is the other one's by then.
        private suspend fun pairShowingItsPin(host: MoonlightHost) {
            val pairing = currentCoroutineContext().job
            try {
                coroutineScope {
                    val pins = launch(start = CoroutineStart.UNDISPATCHED) { moonlight.events.collect { showIfItsPin(host, it) } }
                    moonlight.pairHost(host)
                    pins.cancel()
                }
            } finally {
                if (moonlightPairingJob == pairing) _moonlightPin.value = null
            }
        }

        // Every pairing's PIN goes through the manager, the binding screen's included.
        private fun showIfItsPin(
            host: MoonlightHost,
            event: MoonlightConnectionEvent,
        ) {
            val isItsPin = event is MoonlightConnectionEvent.PairingPinReady && event.host.id == host.id
            if (isItsPin) _moonlightPin.value = MoonlightPinPrompt(hostName = event.host.name, pin = event.pin)
        }
    }
