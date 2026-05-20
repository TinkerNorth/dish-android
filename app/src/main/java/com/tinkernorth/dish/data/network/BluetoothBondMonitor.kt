// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped monitor for bond-state / key-missing events on hosts we've
 * remembered. When a previously-bonded host loses *its* side of the key
 * (peer factory-reset, OS reinstall, BT plist wiped) the system fires
 * [ACTION_KEY_MISSING] and disconnects with `HCI_ERR_AUTH_FAILURE` — invisible
 * to the HID profile callbacks, so the app's only visible symptom is "stuck
 * on Registered, never reaches Connected." This monitor surfaces the actual
 * cause as a Toast so the user knows to forget on both sides and re-pair.
 *
 * Filtered to MACs in [ConnectionStore.rememberedBt] so unrelated bonds
 * (headphones, car, etc.) don't fire spurious alerts. We don't auto-remove
 * the bond from our store on KEY_MISSING — the user may want to re-pair
 * with the same host, in which case the row stays useful as a "Connect"
 * target after they clear the host side.
 */
@Singleton
class BluetoothBondMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: ConnectionStore,
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
                        ACTION_KEY_MISSING -> {
                            Log.w(TAG, "KEY_MISSING for ${remembered.name} ($mac)")
                            show(
                                "Bluetooth bond with ${remembered.name} is stale. " +
                                    "Forget on the host and on this phone, then re-pair.",
                            )
                        }
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                            Log.d(TAG, "BOND_STATE ${remembered.name} ($mac): $prev -> $state")
                            if (prev == BluetoothDevice.BOND_BONDED && state == BluetoothDevice.BOND_NONE) {
                                show("Bluetooth bond with ${remembered.name} was removed — re-pair to reconnect.")
                            }
                        }
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

        private fun show(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
