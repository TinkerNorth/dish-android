package com.tinkernorth.dish.ui.bluetooth

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.P)
@HiltViewModel
class BluetoothGamepadViewModel @Inject constructor(
    private val application: Application
) : ViewModel(), BluetoothGamepad.Listener {

    private val _uiState = MutableStateFlow(BluetoothUiState())
    val uiState: StateFlow<BluetoothUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BluetoothEvent>()
    val events: SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    private var gamepad: BluetoothGamepad? = null
    private val prefs = BluetoothGamepadPrefs(application)

    fun onConnectClicked(hasPermissions: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            viewModelScope.launch { _events.emit(BluetoothEvent.ShowToast("Bluetooth gamepad requires Android 9+")) }
            return
        }

        if (!hasPermissions) {
            viewModelScope.launch { _events.emit(BluetoothEvent.RequestBluetoothPermissions) }
            return
        }

        val state = _uiState.value
        if (state.isConnected) {
            // User-initiated disconnect: forget the host so we don't auto-reconnect next launch.
            prefs.clear()
            gamepad?.disconnect()
            return
        }

        if (state.isRegistered) {
            viewModelScope.launch { _events.emit(BluetoothEvent.RequestDiscoverable) }
            return
        }

        viewModelScope.launch { _events.emit(BluetoothEvent.ShowProfilePicker) }
    }

    fun startGamepad(
        profile: BluetoothGamepad.GamepadProfile,
        autoConnectMac: String? = null,
    ) {
        gamepad?.stop()
        gamepad = BluetoothGamepad(application, this)
        gamepad?.start(profile, autoConnectMac)

        val reconnecting = autoConnectMac != null
        _uiState.update { it.copy(
            status = if (reconnecting) {
                "Reconnecting to last host…"
            } else {
                "Registering as ${profile.profileName}…"
            },
            currentProfileName = profile.profileName,
            usePlayStationLayout = profile == BluetoothGamepad.GamepadProfile.PLAYSTATION,
            isAutoReconnecting = reconnecting,
        )}
    }

    /**
     * If we have a previously-connected host on record, start the HID app with
     * the saved profile and hand the MAC off to [BluetoothGamepad] so it can
     * reconnect as soon as registration completes. Returns true if an auto-start
     * was initiated.
     */
    fun tryAutoStart(hasPermissions: Boolean): Boolean {
        if (!hasPermissions) return false
        if (_uiState.value.isRegistered || _uiState.value.isConnected) return false
        val mac = prefs.lastHostMac ?: return false
        val profileName = prefs.lastProfile ?: return false
        val profile = BluetoothGamepad.GamepadProfile.entries
            .firstOrNull { it.name == profileName } ?: return false
        startGamepad(profile, autoConnectMac = mac)
        return true
    }

    fun sendReport(report: ByteArray) {
        gamepad?.sendReport(report)
    }

    fun buildReport(
        buttons: Int, hat: Int, lx: Short, ly: Short, rx: Short, ry: Short, lt: Int, rt: Int
    ): ByteArray? {
        return gamepad?.buildReport(buttons, hat, lx, ly, rx, ry, lt, rt)
    }

    val isConnected: Boolean get() = _uiState.value.isConnected

    override fun onRegistered() {
        val reconnecting = _uiState.value.isAutoReconnecting
        _uiState.update { it.copy(
            isRegistered = true,
            status = if (reconnecting) {
                "Reconnecting to last host…"
            } else {
                "Registered as ${it.currentProfileName} — tap Advertise to be discoverable"
            },
        )}
        // Skip the Discoverable prompt while auto-reconnecting: BluetoothGamepad
        // will try to restore the link to the saved host directly.
        if (!reconnecting) {
            viewModelScope.launch { _events.emit(BluetoothEvent.RequestDiscoverable) }
        }
    }

    override fun onUnregistered() {
        _uiState.update {
            it.copy(isRegistered = false, isAutoReconnecting = false, status = "Unregistered")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnected(device: BluetoothDevice) {
        // Remember this host so we can auto-reconnect on next launch.
        prefs.lastHostMac = device.address
        prefs.lastProfile = gamepad?.currentProfile?.name
        _uiState.update { it.copy(
            isConnected = true,
            isAutoReconnecting = false,
            connectedDeviceName = device.name ?: device.address,
            status = "Connected to ${device.name ?: device.address}"
        )}
    }

    @SuppressLint("MissingPermission")
    override fun onDisconnected(device: BluetoothDevice) {
        _uiState.update { it.copy(
            isConnected = false,
            isAutoReconnecting = false,
            connectedDeviceName = null,
            status = "Disconnected from ${device.name ?: device.address}"
        )}
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(isAutoReconnecting = false, status = "Error: $message") }
        viewModelScope.launch { _events.emit(BluetoothEvent.ShowToast(message)) }
    }

    override fun onCleared() {
        super.onCleared()
        gamepad?.stop()
        gamepad = null
    }
}
