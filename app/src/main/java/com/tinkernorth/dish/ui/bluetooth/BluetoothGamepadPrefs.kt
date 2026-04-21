package com.tinkernorth.dish.ui.bluetooth

import android.content.Context

/**
 * Small wrapper around [SharedPreferences] that remembers the last Bluetooth
 * HID host the app connected to, so we can auto-reconnect on next launch.
 *
 * Stored values:
 *   - [lastHostMac]  : MAC address of the last connected host (stable across reboots)
 *   - [lastProfile]  : name of the [BluetoothGamepad.GamepadProfile] used
 *   - [lastSlotId]   : id of the controller slot that owned the connection
 */
class BluetoothGamepadPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastHostMac: String?
        get() = prefs.getString(KEY_MAC, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_MAC) else putString(KEY_MAC, value)
            }.apply()
        }

    var lastProfile: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_PROFILE) else putString(KEY_PROFILE, value)
            }.apply()
        }

    var lastSlotId: String?
        get() = prefs.getString(KEY_SLOT_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SLOT_ID) else putString(KEY_SLOT_ID, value)
            }.apply()
        }

    fun save(slotId: String, profileName: String, hostMac: String) {
        prefs.edit()
            .putString(KEY_SLOT_ID, slotId)
            .putString(KEY_PROFILE, profileName)
            .putString(KEY_MAC, hostMac)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_MAC).remove(KEY_PROFILE).remove(KEY_SLOT_ID).apply()
    }

    companion object {
        private const val PREFS_NAME = "bt_gamepad"
        private const val KEY_MAC = "last_host_mac"
        private const val KEY_PROFILE = "last_profile"
        private const val KEY_SLOT_ID = "last_slot_id"
    }
}
