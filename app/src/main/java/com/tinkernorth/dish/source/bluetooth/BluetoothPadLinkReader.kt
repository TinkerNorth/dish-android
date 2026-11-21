// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.annotation.SuppressLint
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
        // The permission is checked, and a revocation caught, by withBondedMatches around the read.
        @SuppressLint("MissingPermission")
        fun linkType(deviceName: String): BluetoothLinkType =
            withBondedMatches(deviceName, BluetoothLinkType.UNKNOWN) { matches ->
                linkTypeOf(matches.map { it.type })
            }

        // The service UUIDs the platform cached when the pad bonded (no discovery), for the one
        // bonded device of that name. Empty when there is no answer: no permission, no adapter,
        // none or several matches.
        @SuppressLint("MissingPermission")
        fun serviceUuids(deviceName: String): List<String> =
            withBondedMatches(deviceName, emptyList()) { matches ->
                matches
                    .singleOrNull()
                    ?.uuids
                    .orEmpty()
                    .map { it.uuid.toString().lowercase() }
            }

        private fun <T> withBondedMatches(
            deviceName: String,
            none: T,
            read: (List<BluetoothDevice>) -> T,
        ): T {
            if (context.checkBluetoothConnectPermission() != PackageManager.PERMISSION_GRANTED) return none
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return none
            return try {
                read(adapter.bondedDevices.orEmpty().filter { it.name == deviceName })
            } catch (e: SecurityException) {
                // Revoked between the check above and the reads: the answer stays empty.
                Log.w(TAG, "BLUETOOTH_CONNECT revoked while reading bonded devices: ${e.message}")
                none
            }
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
