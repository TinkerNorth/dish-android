// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
        @SuppressLint("MissingPermission")
        fun linkType(deviceName: String): BluetoothLinkType {
            if (!connectGranted()) return BluetoothLinkType.UNKNOWN
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return BluetoothLinkType.UNKNOWN
            val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
            val matches = bonded.filter { runCatching { it.name }.getOrNull() == deviceName }
            val device = matches.singleOrNull() ?: return BluetoothLinkType.UNKNOWN
            return when (runCatching { device.type }.getOrNull()) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothLinkType.CLASSIC
                BluetoothDevice.DEVICE_TYPE_LE -> BluetoothLinkType.LOW_ENERGY
                BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothLinkType.DUAL
                else -> BluetoothLinkType.UNKNOWN
            }
        }

        private fun connectGranted(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
    }
