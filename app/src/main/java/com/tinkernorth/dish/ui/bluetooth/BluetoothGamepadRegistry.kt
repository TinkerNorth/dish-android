package com.tinkernorth.dish.ui.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.tinkernorth.dish.data.network.ConnectionStore
import com.tinkernorth.dish.data.network.RememberedBt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped owner for [BluetoothGamepad] instances, keyed by connection
 * id (see [RememberedBt.id]). Android's HID Device profile only allows a
 * single active host per app, but the registry still tracks multiple
 * remembered hosts so the connection hub can expose them all; only one will
 * be [SlotState.registered]/[SlotState.connected] at any time.
 */
@Singleton
class BluetoothGamepadRegistry @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: ConnectionStore,
) {
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

    fun state(connId: String): SlotState = _states.value[connId] ?: SlotState()
    fun isConnected(connId: String): Boolean = state(connId).connected
    fun isActive(connId: String): Boolean = gamepads.containsKey(connId)

    fun addListener(connId: String, l: BluetoothGamepad.Listener) {
        listeners.getOrPut(connId) { mutableListOf() }.add(l)
    }

    fun removeListener(connId: String, l: BluetoothGamepad.Listener) {
        listeners[connId]?.remove(l)
    }

    /**
     * Start a fresh HID registration for [connId] with [profile]. If a
     * different connection is currently active it is torn down first (Android
     * only allows one active HID registration per app).
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun start(
        connId: String,
        profile: BluetoothGamepad.GamepadProfile,
        autoConnectMac: String? = null,
    ) {
        val previouslyActive = gamepads.keys.filter { it != connId }.toList()
        previouslyActive.forEach { stop(it) }
        gamepads[connId]?.stop()
        if (autoConnectMac != null) autoReconnecting.add(connId) else autoReconnecting.remove(connId)
        updateState(connId) {
            it.copy(
                profileName = profile.profileName,
                autoReconnecting = autoConnectMac != null,
                registered = false,
                connected = false,
            )
        }
        val gp = BluetoothGamepad(appContext, dispatchingListener(connId, profile))
        gp.start(profile, autoConnectMac)
        gamepads[connId] = gp
    }

    fun stop(connId: String) {
        gamepads.remove(connId)?.stop()
        autoReconnecting.remove(connId)
        _states.update { it - connId }
    }

    fun stopAll() {
        gamepads.values.forEach { it.stop() }
        gamepads.clear()
        autoReconnecting.clear()
        _states.value = emptyMap()
    }

    fun sendReport(connId: String, report: ByteArray) {
        gamepads[connId]?.sendReport(report)
    }

    fun buildReport(
        connId: String,
        buttons: Int, hat: Int, lx: Short, ly: Short, rx: Short, ry: Short, lt: Int, rt: Int,
    ): ByteArray? = gamepads[connId]?.buildReport(buttons, hat, lx, ly, rx, ry, lt, rt)

    /**
     * If [connId] matches a remembered host, register the HID with its saved
     * profile and try to re-attach to its MAC silently. Returns the profile
     * used or null if nothing was restored.
     *
     * Idempotent: if the slot is already registered/connected we leave the
     * live session alone instead of tearing it down. This matters because
     * [MainActivity] invokes us on every `onCreate`, including configuration
     * changes and re-entry from the gamepad overlay. Also no-ops on API
     * levels below 28 where Bluetooth HID Device is unavailable.
     */
    fun tryAutoReconnect(connId: String): BluetoothGamepad.GamepadProfile? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val entry = store.rememberedBt().firstOrNull { it.id == connId } ?: return null
        val profile = BluetoothGamepad.GamepadProfile.entries
            .firstOrNull { it.name == entry.profileName } ?: return null
        val current = _states.value[connId]
        if (current?.registered == true || current?.connected == true) return profile
        start(connId, profile, autoConnectMac = entry.mac)
        return profile
    }

    fun isAutoReconnecting(connId: String): Boolean = autoReconnecting.contains(connId)

    private fun dispatchingListener(
        connId: String,
        profile: BluetoothGamepad.GamepadProfile,
    ) = object : BluetoothGamepad.Listener {
        override fun onRegistered() {
            updateState(connId) { it.copy(registered = true) }
            listeners[connId]?.toList()?.forEach { it.onRegistered() }
        }
        override fun onUnregistered() {
            autoReconnecting.remove(connId)
            updateState(connId) { it.copy(registered = false, connected = false, autoReconnecting = false) }
            listeners[connId]?.toList()?.forEach { it.onUnregistered() }
        }
        override fun onConnected(device: BluetoothDevice) {
            autoReconnecting.remove(connId)
            val name = device.name ?: device.address
            store.rememberBt(RememberedBt(
                id = connId, name = name, mac = device.address, profileName = profile.name,
            ))
            updateState(connId) { it.copy(connected = true, connectedName = name, autoReconnecting = false) }
            listeners[connId]?.toList()?.forEach { it.onConnected(device) }
        }
        override fun onDisconnected(device: BluetoothDevice) {
            updateState(connId) { it.copy(connected = false, connectedName = null, autoReconnecting = false) }
            listeners[connId]?.toList()?.forEach { it.onDisconnected(device) }
        }
        override fun onError(message: String) {
            autoReconnecting.remove(connId)
            updateState(connId) { it.copy(autoReconnecting = false) }
            listeners[connId]?.toList()?.forEach { it.onError(message) }
        }
    }

    private fun updateState(connId: String, transform: (SlotState) -> SlotState) {
        _states.update { map ->
            val cur = map[connId] ?: SlotState()
            map + (connId to transform(cur))
        }
    }

    companion object { fun idFor(mac: String): String = "bt:$mac" }
}
