// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
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
        store: ConnectionStore,
    ) : ViewModel() {
        val ui: StateFlow<ConnectionsUiState> =
            combine(
                hub.connections,
                satellite.discoveredServers,
                satellite.isScanning,
                satellite.lastScanAtMs,
                store.rememberedBtFlow,
            ) { conns, discovered, scanning, lastScan, rememberedBt ->
                ConnectionsUiState(
                    satelliteRows = satelliteRows(conns, discovered),
                    bluetoothSummaries = bluetoothSummaries(conns),
                    rememberedBtIds = rememberedBt.mapTo(mutableSetOf()) { it.id },
                    scanning = scanning,
                    lastScanAtMs = lastScan,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ConnectionsUiState.Empty)
    }
