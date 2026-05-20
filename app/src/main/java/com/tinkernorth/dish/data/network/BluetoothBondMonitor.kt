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
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.BtStaleReason
import com.tinkernorth.dish.ui.common.DishNotification
import com.tinkernorth.dish.ui.common.DishNotificationQueue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped monitor for bond-state / key-missing events on hosts we've
 * remembered. When a previously-bonded host loses its side of the key
 * (peer factory-reset, OS reinstall, BT plist wiped) the system fires
 * [ACTION_KEY_MISSING] and disconnects with `HCI_ERR_AUTH_FAILURE` —
 * invisible to the HID profile callbacks, so without this monitor the app's
 * only visible symptom is "stuck on Registered, never reaches Connected."
 *
 * Two outputs from one event:
 *
 *   1. **State** — the host id is added to [BluetoothGamepadRegistry.staleBtIds]
 *      with a [BtStaleReason]. The hub lifts the row's [LinkState] to
 *      [LinkState.Stale], so the chip reads "Needs pairing" persistently.
 *      This is the always-on feedback the old toast couldn't give: a user who
 *      dismissed the message could never see it again.
 *   2. **Notification** — a one-shot [DishNotification] with a "SETTINGS"
 *      action that deep-links into the host's Bluetooth device-details
 *      screen. Same severity treatment + brand styling as every other banner
 *      in the app. Toasts are no longer used anywhere.
 */
@Singleton
class BluetoothBondMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: ConnectionStore,
        private val btRegistry: BluetoothGamepadRegistry,
        private val notifications: DishNotificationQueue,
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
            notifications.post(
                severity = DishNotification.Severity.WARN,
                glyph = R.drawable.ic_bluetooth_off,
                title = context.getString(R.string.notif_bt_key_missing_title, remembered.name),
                body = context.getString(R.string.notif_bt_key_missing_body),
                action =
                    DishNotification.Action(
                        label = context.getString(R.string.action_open_settings),
                    ) { openBluetoothDeviceDetails(remembered.mac) },
                // Same-key replacement: a second KEY_MISSING for the same host
                // shouldn't stack a second banner on top of the first.
                key = "bt-stale:${remembered.id}",
                durationMs = DishNotification.DURATION_PERSISTENT,
            )
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
            notifications.post(
                severity = DishNotification.Severity.WARN,
                glyph = R.drawable.ic_bluetooth_off,
                title = context.getString(R.string.notif_bt_bond_removed_title, remembered.name),
                body = context.getString(R.string.notif_bt_bond_removed_body),
                action =
                    DishNotification.Action(
                        label = context.getString(R.string.action_open_settings),
                    ) { openBluetoothDeviceDetails(remembered.mac) },
                key = "bt-stale:${remembered.id}",
                durationMs = DishNotification.DURATION_PERSISTENT,
            )
        }

        /**
         * Deep-link to the host's Bluetooth device-details screen on API 30+
         * so the user lands one tap from the Forget button. Mirrors the
         * intent in [com.tinkernorth.dish.ui.connections.ConnectionsActivity]'s
         * forget flow.
         */
        private fun openBluetoothDeviceDetails(mac: String) {
            val fallback =
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                runCatching { context.startActivity(fallback) }
                return
            }
            val deepLink =
                Intent("android.settings.BLUETOOTH_DEVICE_DETAILS_SETTINGS").apply {
                    putExtra("device_address", mac)
                    data = android.net.Uri.parse("bt-mac:$mac")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            runCatching { context.startActivity(deepLink) }
                .onFailure { runCatching { context.startActivity(fallback) } }
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
