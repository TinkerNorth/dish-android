package com.tinkernorth.dish.ui.bluetooth

import android.bluetooth.BluetoothDevice

data class BluetoothUiState(
    val status: String = "Ready — tap Connect to start",
    val isConnected: Boolean = false,
    val isRegistered: Boolean = false,
    val isAutoReconnecting: Boolean = false,
    val connectedDeviceName: String? = null,
    val currentProfileName: String? = null,
    val usePlayStationLayout: Boolean = false
)

sealed class BluetoothEvent {
    data class ShowToast(val message: String) : BluetoothEvent()
    object RequestBluetoothPermissions : BluetoothEvent()
    object ShowProfilePicker : BluetoothEvent()
    object RequestDiscoverable : BluetoothEvent()
}
