// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam over the Android Bluetooth stack so [BondedHostsRepo] can be
 * unit tested. Implementations must answer without throwing — permission
 * problems are reported via [hasConnectPermission] rather than exceptions
 * because the repo runs on the UI thread and surfaces denial as a row state.
 */
interface BluetoothEnvironment {
    fun hasConnectPermission(): Boolean

    fun bondedDevices(): List<BondedDeviceSnapshot>

    /** MAC addresses the framework currently has connected via the HID Device profile. */
    fun connectedHidHostMacs(): Set<String>
}

@Singleton
@SuppressLint("MissingPermission") // every framework call is gated by hasConnectPermission().
class AndroidBluetoothEnvironment
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BluetoothEnvironment {
        override fun hasConnectPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        }

        override fun bondedDevices(): List<BondedDeviceSnapshot> {
            if (!hasConnectPermission()) return emptyList()
            val adapter = manager()?.adapter ?: return emptyList()
            return runCatching {
                adapter.bondedDevices.orEmpty().map { d ->
                    val cls = d.bluetoothClass
                    BondedDeviceSnapshot(
                        mac = d.address,
                        name = runCatching { d.name }.getOrNull(),
                        majorClass = cls?.majorDeviceClass ?: -1,
                        minorClass = cls?.deviceClass ?: -1,
                    )
                }
            }.getOrDefault(emptyList())
        }

        override fun connectedHidHostMacs(): Set<String> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
            if (!hasConnectPermission()) return emptySet()
            val manager = manager() ?: return emptySet()
            return runCatching {
                manager
                    .getConnectedDevices(BluetoothProfile.HID_DEVICE)
                    .map { it.address }
                    .toSet()
            }.getOrDefault(emptySet())
        }

        private fun manager(): BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
