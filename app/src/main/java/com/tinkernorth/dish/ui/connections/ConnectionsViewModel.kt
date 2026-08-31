// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel
    @Inject
    constructor(
        hub: ConnectionCoordinator,
        satellite: SatelliteConnectionManager,
        moonlight: MoonlightConnectionManager,
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
    }
