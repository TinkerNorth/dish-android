package com.tinkernorth.dish.ui.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped owner for the per-slot [BluetoothGamepad] instances.
 *
 * The registry survives activity transitions, so the overlay activity and the
 * dashboard activity can share the same HID sessions. It also owns the
 * [BluetoothGamepadPrefs] persistence and the auto-reconnect flow.
 *
 * Activities attach a [BluetoothGamepad.Listener] with [addListener] to be
 * notified of lifecycle events for a specific slot.
 */
@Singleton
class BluetoothGamepadRegistry @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val prefs = BluetoothGamepadPrefs(appContext)
    private val gamepads = mutableMapOf<String, BluetoothGamepad>()
    private val listeners = mutableMapOf<String, MutableList<BluetoothGamepad.Listener>>()
    private val autoReconnecting = mutableSetOf<String>()

    data class SlotState(
        val registered: Boolean = false,
        val connected: Boolean = false,
        val connectedName: String? = null,
        val profileName: String? = null,
        val autoReconnecting: Boolean = false,
    )

    private val _states = MutableStateFlow<Map<String, SlotState>>(emptyMap())
    val states: StateFlow<Map<String, SlotState>> = _states.asStateFlow()

    fun state(slotId: String): SlotState = _states.value[slotId] ?: SlotState()

    fun isConnected(slotId: String): Boolean = state(slotId).connected
    fun isActive(slotId: String): Boolean = gamepads.containsKey(slotId)

    fun addListener(slotId: String, l: BluetoothGamepad.Listener) {
        listeners.getOrPut(slotId) { mutableListOf() }.add(l)
    }

    fun removeListener(slotId: String, l: BluetoothGamepad.Listener) {
        listeners[slotId]?.remove(l)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun start(
        slotId: String,
        profile: BluetoothGamepad.GamepadProfile,
        autoConnectMac: String? = null,
    ) {
        gamepads[slotId]?.stop()
        if (autoConnectMac != null) autoReconnecting.add(slotId) else autoReconnecting.remove(slotId)
        updateState(slotId) {
            it.copy(
                profileName = profile.profileName,
                autoReconnecting = autoConnectMac != null,
                registered = false,
                connected = false,
            )
        }

        val gp = BluetoothGamepad(appContext, dispatchingListener(slotId, profile))
        gp.start(profile, autoConnectMac)
        gamepads[slotId] = gp
    }

    fun stop(slotId: String) {
        gamepads.remove(slotId)?.stop()
        autoReconnecting.remove(slotId)
        _states.update { it - slotId }
    }

    fun stopAll() {
        gamepads.values.forEach { it.stop() }
        gamepads.clear()
        autoReconnecting.clear()
        _states.value = emptyMap()
    }

    fun sendReport(slotId: String, report: ByteArray) {
        gamepads[slotId]?.sendReport(report)
    }

    fun buildReport(
        slotId: String,
        buttons: Int, hat: Int, lx: Short, ly: Short, rx: Short, ry: Short, lt: Int, rt: Int,
    ): ByteArray? = gamepads[slotId]?.buildReport(buttons, hat, lx, ly, rx, ry, lt, rt)

    /** Clears the persisted host when the user intentionally disconnects this slot. */
    fun forgetHost(slotId: String) {
        if (prefs.lastSlotId == slotId) prefs.clear()
    }

    /**
     * If a previously-connected host is on record for [slotId], start a silent
     * re-registration + reconnect. Returns the saved [GamepadProfile] name on
     * success, null if nothing was restored.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun tryAutoReconnect(slotId: String): BluetoothGamepad.GamepadProfile? {
        if (prefs.lastSlotId != slotId) return null
        val mac = prefs.lastHostMac ?: return null
        val profileName = prefs.lastProfile ?: return null
        val profile = BluetoothGamepad.GamepadProfile.entries
            .firstOrNull { it.name == profileName } ?: return null
        start(slotId, profile, autoConnectMac = mac)
        return profile
    }

    private fun dispatchingListener(
        slotId: String,
        profile: BluetoothGamepad.GamepadProfile,
    ) = object : BluetoothGamepad.Listener {
        override fun onRegistered() {
            updateState(slotId) { it.copy(registered = true) }
            listeners[slotId]?.toList()?.forEach { it.onRegistered() }
        }
        override fun onUnregistered() {
            autoReconnecting.remove(slotId)
            updateState(slotId) { it.copy(registered = false, connected = false, autoReconnecting = false) }
            listeners[slotId]?.toList()?.forEach { it.onUnregistered() }
        }
        override fun onConnected(device: BluetoothDevice) {
            autoReconnecting.remove(slotId)
            prefs.save(slotId, profile.name, device.address)
            val name = device.name ?: device.address
            updateState(slotId) { it.copy(connected = true, connectedName = name, autoReconnecting = false) }
            listeners[slotId]?.toList()?.forEach { it.onConnected(device) }
        }
        override fun onDisconnected(device: BluetoothDevice) {
            updateState(slotId) { it.copy(connected = false, connectedName = null, autoReconnecting = false) }
            listeners[slotId]?.toList()?.forEach { it.onDisconnected(device) }
        }
        override fun onError(message: String) {
            autoReconnecting.remove(slotId)
            updateState(slotId) { it.copy(autoReconnecting = false) }
            listeners[slotId]?.toList()?.forEach { it.onError(message) }
        }
    }

    private fun updateState(slotId: String, transform: (SlotState) -> SlotState) {
        _states.update { map ->
            val cur = map[slotId] ?: SlotState()
            map + (slotId to transform(cur))
        }
    }

    fun isAutoReconnecting(slotId: String): Boolean = autoReconnecting.contains(slotId)
}
