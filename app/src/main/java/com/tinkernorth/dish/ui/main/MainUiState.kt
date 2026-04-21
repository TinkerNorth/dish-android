package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.data.model.DiscoveredServer

// ── Per-slot types ───────────────────────────────────────────────────────

enum class SlotInputType { VIRTUAL, PHYSICAL }

enum class SlotDestType { NONE, WIFI, BLUETOOTH }

enum class SlotConnectionState { IDLE, CONNECTING, CONNECTED }

/**
 * One controller slot. The virtual controller is always present (id = "virtual").
 * Physical controllers appear when Android detects a gamepad InputDevice.
 * Each slot independently chooses a destination and manages its own connection.
 */
data class ControllerSlot(
    val id: String,
    val inputType: SlotInputType,
    val name: String,
    val physicalDeviceId: Int = -1,

    // Destination
    val destType: SlotDestType = SlotDestType.NONE,
    val destExpanded: Boolean = false,

    // WiFi destination
    val wifiServer: DiscoveredServer? = null,

    // Bluetooth destination
    val btProfileName: String? = null,
    val btRegistered: Boolean = false,

    // Connection
    val connectionState: SlotConnectionState = SlotConnectionState.IDLE,
    val connectedName: String? = null,  // server name or BT device name

    // Disconnecting state (for physical controllers unplugged)
    val isDisconnecting: Boolean = false,
    val disconnectTimeLeft: Int = 0,
) {
    val isConnected get() = connectionState == SlotConnectionState.CONNECTED
    val usePlayStationLayout get() = btProfileName == "PlayStation"
}

// ── Top-level UI state ───────────────────────────────────────────────────

data class MainUiState(
    val slots: List<ControllerSlot> = listOf(
        ControllerSlot(id = VIRTUAL_SLOT_ID, inputType = SlotInputType.VIRTUAL, name = "Virtual Controller")
    ),

    /** Shared scan results — any slot's WiFi destination picker shows these. */
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isScanning: Boolean = false,
) {
    val virtualSlot get() = slots.first { it.id == VIRTUAL_SLOT_ID }
    val physicalSlots get() = slots.filter { it.inputType == SlotInputType.PHYSICAL }
    val anyConnected get() = slots.any { it.isConnected }
}

const val VIRTUAL_SLOT_ID = "virtual"

// ── Events ───────────────────────────────────────────────────────────────

sealed class MainEvent {
    data class ShowToast(val message: String) : MainEvent()
    data class ShowPairingDialog(val server: DiscoveredServer, val slotId: String) : MainEvent()
    data class RequestBluetoothPermissions(val slotId: String) : MainEvent()
    data class RequestDiscoverable(val slotId: String) : MainEvent()
}
