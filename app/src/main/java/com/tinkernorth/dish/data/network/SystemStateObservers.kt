// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// ─── Bluetooth adapter ──────────────────────────────────────────────────────

/** Live Bluetooth adapter availability. Mirrors `BluetoothAdapter.getState()`. */
enum class BluetoothAdapterState {
    /** No Bluetooth hardware (rare, mostly emulators / tablets). */
    UNSUPPORTED,

    /** Hardware present; adapter currently off. User can turn on from settings. */
    OFF,

    /** Adapter on. */
    ON,
}

/**
 * Process-scoped observer for the Bluetooth adapter's on/off state. The
 * activities use this for two things:
 *
 *  - Render a persistent in-section banner on Connections when the adapter is
 *    off, with a "TURN ON" CTA. Without this the user had to discover their
 *    adapter was off by tapping Add and getting a one-shot toast.
 *  - Live-update on `BluetoothAdapter.ACTION_STATE_CHANGED` so the banner
 *    disappears the moment the user toggles BT on, no manual refresh needed.
 */
@Singleton
class BluetoothAdapterStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DefaultLifecycleObserver {
        private val _state = MutableStateFlow(initialState())
        val state: StateFlow<BluetoothAdapterState> = _state.asStateFlow()

        @Volatile private var registered = false

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    refresh()
                }
            }

        override fun onStart(owner: LifecycleOwner) {
            if (registered) return
            refresh()
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!registered) return
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }

        private fun refresh() {
            _state.value = initialState()
        }

        private fun initialState(): BluetoothAdapterState {
            val adapter = adapter() ?: return BluetoothAdapterState.UNSUPPORTED
            return if (adapter.isEnabled) BluetoothAdapterState.ON else BluetoothAdapterState.OFF
        }

        private fun adapter(): BluetoothAdapter? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }
    }

// ─── Bluetooth permission ────────────────────────────────────────────────────

/** Whether the user has granted BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE on API 31+. */
enum class BluetoothPermissionState {
    /** Not required on this API (pre-31). */
    NOT_REQUIRED,

    /** Required and granted. */
    GRANTED,

    /** Required and at least one permission denied / never asked. */
    DENIED,
}

/**
 * Re-checks the BT runtime permission state on every process foreground.
 * Permissions can be revoked while the app is backgrounded (App Info → Force
 * stop → Permissions, or the OS auto-revoke after long inactivity); without
 * a re-check on resume the activity would silently fail on the next
 * auto-reconnect.
 */
@Singleton
class BluetoothPermissionStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DefaultLifecycleObserver {
        private val _state = MutableStateFlow(currentState())
        val state: StateFlow<BluetoothPermissionState> = _state.asStateFlow()

        override fun onStart(owner: LifecycleOwner) {
            // Re-poll on every foreground — the OS doesn't broadcast revocation.
            _state.value = currentState()
        }

        /** Force a re-poll (called after [ConnectionsActivity]'s permission launcher). */
        fun refresh() {
            _state.value = currentState()
        }

        private fun currentState(): BluetoothPermissionState {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return BluetoothPermissionState.NOT_REQUIRED
            val needed =
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                )
            val allGranted =
                needed.all {
                    ContextCompat.checkSelfPermission(context, it) ==
                        PackageManager.PERMISSION_GRANTED
                }
            return if (allGranted) BluetoothPermissionState.GRANTED else BluetoothPermissionState.DENIED
        }
    }

// ─── Network connectivity ────────────────────────────────────────────────────

/**
 * What kind of network is currently the system default. Discovery + connect
 * paths branch on this — a satellite session needs the same Wi-Fi LAN as the
 * host PC, so "mobile data only" is a guaranteed-failure mode worth surfacing
 * before the user taps Scan.
 */
enum class NetworkState {
    /** No usable network — airplane mode, hotel captive portal pre-auth, etc. */
    NONE,

    /** Cellular only — satellites won't be reachable. */
    CELLULAR,

    /** Wi-Fi (or any non-cellular LAN-class transport — Ethernet, dock-USB tether). */
    WIFI,
}

/**
 * Process-scoped Wi-Fi vs. cellular tracker. Discovery + auto-reconnect
 * branches on this so the user gets actionable feedback when LAN can't see a
 * satellite ("Connect to Wi-Fi") instead of a generic "no satellites found".
 */
@Singleton
class NetworkStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DefaultLifecycleObserver {
        private val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val _state = MutableStateFlow(currentState())
        val state: StateFlow<NetworkState> = _state.asStateFlow()

        @Volatile private var registered = false

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _state.value = currentState()
                }

                override fun onLost(network: Network) {
                    _state.value = currentState()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    _state.value = currentState()
                }
            }

        override fun onStart(owner: LifecycleOwner) {
            if (registered) return
            // Re-seed because callbacks aren't guaranteed to fire for the
            // already-current network on registration.
            _state.value = currentState()
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            runCatching { cm.registerNetworkCallback(request, callback) }
            registered = true
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!registered) return
            runCatching { cm.unregisterNetworkCallback(callback) }
            registered = false
        }

        private fun currentState(): NetworkState {
            val active = cm.activeNetwork ?: return NetworkState.NONE
            val caps = cm.getNetworkCapabilities(active) ?: return NetworkState.NONE
            return when {
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.NONE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
                else -> NetworkState.NONE
            }
        }
    }
