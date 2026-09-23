// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothConnections
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val connectedNames = ConcurrentHashMap.newKeySet<String>()

        @Volatile private var onChanged: (() -> Unit)? = null

        @Volatile private var started = false

        fun start(onChanged: () -> Unit) {
            this.onChanged = onChanged
            if (started) return
            started = true
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                }
            runCatching {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }

        fun isConnected(name: String): Boolean {
            if (name.isBlank() || connectedNames.isEmpty()) return false
            val target = name.trim()
            return connectedNames.any { it.equals(target, ignoreCase = true) }
        }

        private inner class BondedDeviceReceiver : BroadcastReceiver() {
            override fun onReceive(
                received: Context?,
                intent: Intent?,
            ) {
                val name = deviceName(intent) ?: return
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> connectedNames.add(name)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> connectedNames.remove(name)
                    else -> return
                }
                onChanged?.invoke()
            }
        }

        private val receiver = BondedDeviceReceiver()

        private fun deviceName(intent: Intent?): String? {
            if (context.checkBluetoothConnectPermission() != PackageManager.PERMISSION_GRANTED) return null
            val device = intent?.let { IntentCompat.getParcelableExtra(it, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) }
            val name =
                try {
                    device?.name
                } catch (e: SecurityException) {
                    // Revoked between the check above and the read: the broadcast carries no name for us.
                    Log.w(TAG, "BLUETOOTH_CONNECT revoked while reading a device name: ${e.message}")
                    null
                }
            return name?.trim()?.takeIf { it.isNotEmpty() }
        }

        private companion object {
            const val TAG = "BluetoothConnections"
        }
    }
