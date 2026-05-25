// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
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
        val bonded =
            try {
                adapter()?.bondedDevices ?: return null
            } catch (e: SecurityException) {
                Log.d(TAG, "bondedDevices blocked: ${e.message}")
                return null
            }
        val byName: Map<String, BluetoothDevice> =
            bonded
                .mapNotNull { dev ->
                    val n =
                        try {
                            dev.name
                        } catch (_: SecurityException) {
                            null
                        }
                    n?.let { it to dev }
                }.toMap()
        val matchName = matchBondedDeviceName(name, byName.keys) ?: return null
        return byName[matchName]
    }

    private fun adapter(): BluetoothAdapter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

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
