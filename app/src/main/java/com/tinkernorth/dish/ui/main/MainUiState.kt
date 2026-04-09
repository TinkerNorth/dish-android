package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.data.model.ControllerEntry
import com.tinkernorth.dish.data.model.DiscoveredServer

data class MainUiState(
    val controllers: List<ControllerEntry> = emptyList(),
    val isConnected: Boolean = false,
    val connectedServerName: String? = null,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isScanning: Boolean = false
)

sealed class MainEvent {
    data class ShowToast(val message: String) : MainEvent()
    data class ShowPairingDialog(val server: DiscoveredServer) : MainEvent()
}
