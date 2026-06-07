// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressLint("MissingPermission")
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

        private val receiver =
            object : BroadcastReceiver() {
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

        private fun deviceName(intent: Intent?): String? {
            if (!hasPermission()) return null
            val device =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            return runCatching { device?.name }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun hasPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
