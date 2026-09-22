// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class BluetoothLinkType { UNKNOWN, CLASSIC, LOW_ENERGY, DUAL }

// The framework never exposes a pad's Bluetooth address, so the bonded list is matched by name;
// an ambiguous name stays unknown rather than guessing.
@Singleton
class BluetoothPadLinkReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun linkType(deviceName: String): BluetoothLinkType {
            if (context.checkBluetoothConnectPermission() != PackageManager.PERMISSION_GRANTED) return BluetoothLinkType.UNKNOWN
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return BluetoothLinkType.UNKNOWN
            return try {
                val matches = adapter.bondedDevices.orEmpty().filter { it.name == deviceName }
                when (matches.singleOrNull()?.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothLinkType.CLASSIC
                    BluetoothDevice.DEVICE_TYPE_LE -> BluetoothLinkType.LOW_ENERGY
                    BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothLinkType.DUAL
                    else -> BluetoothLinkType.UNKNOWN
                }
            } catch (e: SecurityException) {
                // Revoked between the check above and the reads: the link type stays unknown.
                Log.w(TAG, "BLUETOOTH_CONNECT revoked while reading bonded devices: ${e.message}")
                BluetoothLinkType.UNKNOWN
            }
        }

        private companion object {
            const val TAG = "BluetoothPadLinkReader"
        }
    }
