// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
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
    ) : ViewModel() {
        // The satellite/BT half of the state, so the Moonlight flows fit in one more combine.
        private data class SatBtSlice(
            val satelliteRows: List<SatelliteRow>,
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
                    satelliteRows = satelliteRows(conns, discovered),
                    bluetoothSummaries = bluetoothSummaries(conns),
                    rememberedBtIds = rememberedBt.mapTo(mutableSetOf()) { it.id },
                    scanning = scanning,
                    lastScanAtMs = lastScan,
                )
            }

        val ui: StateFlow<ConnectionsUiState> =
            combine(
                satBt,
                hub.connections,
                moonlight.discovered,
                moonlight.isScanning,
                moonlight.remembered,
            ) { slice, conns, moonlightDiscovered, moonlightScanning, moonlightRemembered ->
                ConnectionsUiState(
                    satelliteRows = slice.satelliteRows,
                    bluetoothSummaries = slice.bluetoothSummaries,
                    moonlightRows =
                        moonlightRows(
                            conns,
                            moonlightDiscovered,
                            moonlightRemembered.mapTo(mutableSetOf()) { it.id },
                        ),
                    rememberedBtIds = slice.rememberedBtIds,
                    scanning = slice.scanning,
                    moonlightScanning = moonlightScanning,
                    lastScanAtMs = slice.lastScanAtMs,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ConnectionsUiState.Empty)
    }
