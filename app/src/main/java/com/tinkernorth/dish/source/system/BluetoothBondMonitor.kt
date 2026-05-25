// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BtStaleReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothBondMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: ConnectionStore,
        private val btRegistry: BluetoothGamepadRegistry,
    ) : DefaultLifecycleObserver {
        @Volatile private var registered: Boolean = false

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val device = extractDevice(intent) ?: return
                    val mac = device.address ?: return
                    val remembered =
                        store.rememberedBt().firstOrNull { it.mac.equals(mac, ignoreCase = true) }
                            ?: return
                    when (intent.action) {
                        ACTION_KEY_MISSING -> handleKeyMissing(remembered)
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                            handleBondStateChange(intent, remembered)
                    }
                }
            }

        override fun onStart(owner: LifecycleOwner) {
            if (registered) return
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(ACTION_KEY_MISSING)
                }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!registered) return
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }

        private fun handleKeyMissing(remembered: RememberedBt) {
            Log.w(TAG, "KEY_MISSING for ${remembered.name} (${remembered.mac})")
            btRegistry.markStale(remembered.id, BtStaleReason.KEY_MISSING)
        }

        private fun handleBondStateChange(
            intent: Intent,
            remembered: RememberedBt,
        ) {
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            Log.d(TAG, "BOND_STATE ${remembered.name} (${remembered.mac}): $prev -> $state")
            if (prev != BluetoothDevice.BOND_BONDED || state != BluetoothDevice.BOND_NONE) return
            btRegistry.markStale(remembered.id, BtStaleReason.BOND_REMOVED)
        }

        @Suppress("DEPRECATION")
        private fun extractDevice(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        private companion object {
            const val TAG = "DishBT"

            // Public from API 35; raw string keeps us building against earlier SDKs.
            const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
        }
    }
