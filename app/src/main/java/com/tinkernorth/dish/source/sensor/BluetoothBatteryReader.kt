// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

class BluetoothBatteryReader(
    private val context: Context,
) {
    private var batteryLevelMethod: java.lang.reflect.Method? = null

    @Volatile private var resolved = false

    fun readLevel(inputDeviceName: String): Int? {
        val device = bondedDeviceNamed(inputDeviceName) ?: return null
        val method = batteryLevelMethod() ?: return null
        return try {
            val raw = method.invoke(device) as? Int ?: return null
            raw.takeIf { it in 0..100 }
        } catch (e: ReflectiveOperationException) {
            Log.d(TAG, "getBatteryLevel reflection failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "getBatteryLevel rejected the call: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.d(TAG, "getBatteryLevel blocked: ${e.message}")
            null
        }
    }

    private fun bondedDeviceNamed(name: String): BluetoothDevice? {
        val bonded = bondedDevices() ?: return null
        val byName = bonded.mapNotNull(::nameToDevice).toMap()
        val matchName = matchBondedDeviceName(name, byName.keys) ?: return null
        return byName[matchName]
    }

    // Null rather than empty: a permission that was refused is not the same as a phone with no
    // bonded devices, and the caller must not treat it as one.
    private fun bondedDevices(): Set<BluetoothDevice>? =
        try {
            adapter()?.bondedDevices
        } catch (e: SecurityException) {
            Log.d(TAG, "bondedDevices blocked: ${e.message}")
            null
        }

    // A single device can have its name withheld while its neighbours do not, so this drops one
    // rather than failing the sweep.
    private fun nameToDevice(device: BluetoothDevice): Pair<String, BluetoothDevice>? =
        try {
            device.name?.let { it to device }
        } catch (_: SecurityException) {
            null
        }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun batteryLevelMethod(): java.lang.reflect.Method? {
        if (resolved) return batteryLevelMethod
        batteryLevelMethod =
            try {
                BluetoothDevice::class.java.getMethod("getBatteryLevel")
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "getBatteryLevel not present on this ROM: ${e.message}")
                null
            }
        resolved = true
        return batteryLevelMethod
    }

    companion object {
        private const val TAG = "BluetoothBatteryReader"

        fun matchBondedDeviceName(
            inputDeviceName: String,
            bondedNames: Collection<String>,
        ): String? {
            val target = inputDeviceName.trim()
            if (target.isEmpty()) return null
            return bondedNames.firstOrNull { it.trim().equals(target, ignoreCase = true) }
        }
    }
}
