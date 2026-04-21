package com.tinkernorth.dish.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ServerConnectionManager
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
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val serverManager: ServerConnectionManager,
    val controllerManager: ControllerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    init {
        // Merge server state + physical controller list into slot list
        combine(
            serverManager.isConnected,
            serverManager.connectedServer,
            serverManager.discoveredServers,
            serverManager.isScanning,
            controllerManager.controllers
        ) { wifiConnected, connServer, servers, scanning, physControllers ->
            val prev = _uiState.value
            // Rebuild physical slots from detected controllers
            val physSlots = physControllers.map { ctrl ->
                val existing = prev.slots.find { it.id == ctrl.id.toString() }
                (existing ?: ControllerSlot(
                    id = ctrl.id.toString(),
                    inputType = SlotInputType.PHYSICAL,
                    name = ctrl.name,
                    physicalDeviceId = ctrl.id,
                )).let { slot ->
                    // If this slot is WiFi-connected, update from shared ServerConnectionManager
                    if (slot.destType == SlotDestType.WIFI && wifiConnected && connServer == slot.wifiServer)
                        slot.copy(connectionState = SlotConnectionState.CONNECTED, connectedName = connServer?.name)
                    else if (slot.destType == SlotDestType.WIFI && !wifiConnected && slot.connectionState == SlotConnectionState.CONNECTED)
                        slot.copy(connectionState = SlotConnectionState.IDLE, connectedName = null)
                    else slot
                }.copy(
                    isDisconnecting = ctrl.isDisconnected,
                    disconnectTimeLeft = ctrl.disconnectTimeLeft,
                )
            }
            // Keep the virtual slot (always first), update its wifi state too
            val vSlot = prev.virtualSlot.let { slot ->
                if (slot.destType == SlotDestType.WIFI && wifiConnected && connServer == slot.wifiServer)
                    slot.copy(connectionState = SlotConnectionState.CONNECTED, connectedName = connServer?.name)
                else if (slot.destType == SlotDestType.WIFI && !wifiConnected && slot.connectionState == SlotConnectionState.CONNECTED)
                    slot.copy(connectionState = SlotConnectionState.IDLE, connectedName = null)
                else slot
            }
            prev.copy(
                slots = listOf(vSlot) + physSlots,
                discoveredServers = servers,
                isScanning = scanning,
            )
        }.onEach { _uiState.value = it }.launchIn(viewModelScope)

        // Forward connection events
        serverManager.events.onEach { event ->
            when (event) {
                is ConnectionEvent.PairingRequired -> _events.emit(MainEvent.ShowPairingDialog(event.server, ""))
                is ConnectionEvent.Error -> _events.emit(MainEvent.ShowToast(event.message))
            }
        }.launchIn(viewModelScope)
    }

    // ── Slot: tap to expand/collapse ──────────────────────────────────────

    fun toggleSlotExpanded(slotId: String) {
        _uiState.update { st ->
            st.copy(slots = st.slots.map {
                if (it.id == slotId) it.copy(destExpanded = !it.destExpanded) else it
            })
        }
    }

    // ── Slot: choose destination type ─────────────────────────────────────

    fun setSlotDestWifi(slotId: String) {
        _uiState.update { st ->
            st.copy(slots = st.slots.map {
                if (it.id == slotId) it.copy(destType = SlotDestType.WIFI) else it
            })
        }
    }

    fun setSlotDestBt(slotId: String) {
        _uiState.update { st ->
            st.copy(slots = st.slots.map {
                if (it.id == slotId) it.copy(destType = SlotDestType.BLUETOOTH) else it
            })
        }
    }

    // ── WiFi actions ──────────────────────────────────────────────────────

    fun onScanClicked() {
        serverManager.startDiscovery()
    }

    fun onServerSelected(slotId: String, server: DiscoveredServer) {
        _uiState.update { st ->
            st.copy(slots = st.slots.map {
                if (it.id == slotId) it.copy(
                    wifiServer = server,
                    connectionState = SlotConnectionState.CONNECTING,
                ) else it
            })
        }
        serverManager.selectServer(server)
    }

    fun onPairingPinEntered(server: DiscoveredServer, pin: String) {
        serverManager.pairWithPin(server, pin)
    }

    // ── Bluetooth callbacks (called from Activity) ────────────────────────

    fun onBtRegistered(slotId: String, requestDiscoverable: Boolean = true) {
        updateSlot(slotId) { it.copy(btRegistered = true, connectionState = SlotConnectionState.CONNECTING) }
        if (requestDiscoverable) {
            _events.tryEmit(MainEvent.RequestDiscoverable(slotId))
        }
    }

    fun onBtConnected(slotId: String, deviceName: String) {
        updateSlot(slotId) { it.copy(connectionState = SlotConnectionState.CONNECTED, connectedName = deviceName) }
    }

    fun onBtDisconnected(slotId: String) {
        updateSlot(slotId) { it.copy(connectionState = SlotConnectionState.IDLE, connectedName = null, btRegistered = false) }
    }

    fun onBtError(slotId: String, msg: String) {
        updateSlot(slotId) { it.copy(connectionState = SlotConnectionState.IDLE, btRegistered = false) }
        _events.tryEmit(MainEvent.ShowToast(msg))
    }

    fun setBtProfile(slotId: String, profileName: String) {
        updateSlot(slotId) { it.copy(btProfileName = profileName) }
    }

    // ── Disconnect a single slot ──────────────────────────────────────────

    fun disconnectSlot(slotId: String) {
        val slot = _uiState.value.slots.find { it.id == slotId } ?: return
        when (slot.destType) {
            SlotDestType.WIFI -> serverManager.disconnect()
            SlotDestType.BLUETOOTH -> {} // Activity handles BT teardown
            SlotDestType.NONE -> {}
        }
        updateSlot(slotId) {
            it.copy(connectionState = SlotConnectionState.IDLE, connectedName = null, btRegistered = false,
                wifiServer = null, destType = SlotDestType.NONE)
        }
    }

    // ── Physical controller management ────────────────────────────────────

    fun onControllerConnected(id: Int, name: String) {
        controllerManager.addController(id, name)
    }

    fun onControllerDisconnected(id: Int) {
        controllerManager.removeController(id)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun updateSlot(slotId: String, transform: (ControllerSlot) -> ControllerSlot) {
        _uiState.update { st ->
            st.copy(slots = st.slots.map { if (it.id == slotId) transform(it) else it })
        }
    }
}
