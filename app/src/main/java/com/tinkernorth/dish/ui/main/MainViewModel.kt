package com.tinkernorth.dish.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.data.model.ControllerEntry
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ServerConnectionManager
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
import com.tinkernorth.dish.ui.common.ControllerManager
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
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val serverManager: ServerConnectionManager,
    val controllerManager: ControllerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    init {
        // Combine all source flows into a single UI state
        combine(
            serverManager.isConnected,
            serverManager.connectedServer,
            serverManager.discoveredServers,
            serverManager.isScanning,
            controllerManager.controllers
        ) { isConnected: Boolean, connectedServer: DiscoveredServer?, servers: List<DiscoveredServer>, isScanning: Boolean, controllers: List<ControllerEntry> ->
            MainUiState(
                controllers = controllers,
                isConnected = isConnected,
                connectedServerName = connectedServer?.name,
                discoveredServers = servers,
                isScanning = isScanning
            )
        }.onEach { newState ->
            _uiState.value = newState
        }.launchIn(viewModelScope)

        // Forward connection events to UI events
        serverManager.events.onEach { event ->
            when (event) {
                is ConnectionEvent.PairingRequired -> _events.emit(MainEvent.ShowPairingDialog(event.server))
                is ConnectionEvent.Error -> _events.emit(MainEvent.ShowToast(event.message))
            }
        }.launchIn(viewModelScope)
    }

    fun onScanClicked() {
        serverManager.startDiscovery()
    }

    fun onServerSelected(server: DiscoveredServer) {
        serverManager.selectServer(server)
    }

    fun onPairingPinEntered(server: DiscoveredServer, pin: String) {
        serverManager.pairWithPin(server, pin)
    }

    fun onDisconnectClicked() {
        serverManager.disconnect()
    }

    fun onControllerConnected(id: Int, name: String) {
        controllerManager.addController(id, name)
    }

    fun onControllerDisconnected(id: Int) {
        controllerManager.removeController(id)
    }
}
