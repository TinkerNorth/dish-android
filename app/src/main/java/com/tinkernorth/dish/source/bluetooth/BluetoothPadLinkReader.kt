// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothAdapter
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
        // Each read below makes its own permission-gated platform calls inside its own try, so the
        // revocation between the permission check and the read is caught where the calls are.
        fun linkType(deviceName: String): BluetoothLinkType {
            val adapter = adapterWithPermission() ?: return BluetoothLinkType.UNKNOWN
            return try {
                val types =
                    adapter.bondedDevices
                        .orEmpty()
                        .filter { it.name == deviceName }
                        .map { it.type }
                linkTypeOf(types)
            } catch (e: SecurityException) {
                revoked(e)
                BluetoothLinkType.UNKNOWN
            }
        }

        // The service UUIDs the platform cached when the pad bonded (no discovery), for the one
        // bonded device of that name. Empty when there is no answer: no permission, no adapter,
        // none or several matches.
        fun serviceUuids(deviceName: String): List<String> {
            val adapter = adapterWithPermission() ?: return emptyList()
            return try {
                adapter.bondedDevices
                    .orEmpty()
                    .filter { it.name == deviceName }
                    .singleOrNull()
                    ?.uuids
                    .orEmpty()
                    .map { it.uuid.toString().lowercase() }
            } catch (e: SecurityException) {
                revoked(e)
                emptyList()
            }
        }

        private fun adapterWithPermission(): BluetoothAdapter? {
            if (context.checkBluetoothConnectPermission() != PackageManager.PERMISSION_GRANTED) return null
            return (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }

        private fun revoked(e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT revoked while reading bonded devices: ${e.message}")
        }

        private companion object {
            const val TAG = "BluetoothPadLinkReader"
        }
    }

// Exactly one bonded device of the pad's name is the pad; none or several is no answer.
internal fun linkTypeOf(matchingDeviceTypes: List<Int>): BluetoothLinkType =
    when (matchingDeviceTypes.singleOrNull()) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothLinkType.CLASSIC
        BluetoothDevice.DEVICE_TYPE_LE -> BluetoothLinkType.LOW_ENERGY
        BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothLinkType.DUAL
        else -> BluetoothLinkType.UNKNOWN
    }
