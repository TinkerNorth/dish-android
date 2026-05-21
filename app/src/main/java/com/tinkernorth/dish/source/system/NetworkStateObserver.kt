// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What kind of network is currently the system default. Discovery + connect paths
 * branch on this — a satellite session needs the same Wi-Fi LAN as the host PC, so
 * "mobile data only" is a guaranteed-failure mode worth surfacing before the user
 * taps Scan.
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
 * Process-scoped Wi-Fi vs. cellular tracker. Discovery + auto-reconnect branches on
 * this so the user gets actionable feedback when LAN can't see a satellite
 * ("Connect to Wi-Fi") instead of a generic "no satellites found".
 *
 * **Pattern:** [AbstractStateSource]`<NetworkState>` — owns a
 * `ConnectivityManager.NetworkCallback`, publishes state, no events.
 */
@Singleton
class NetworkStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AbstractStateSource<NetworkState>(NetworkState.NONE) {
        private val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Volatile private var registered = false

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    setState(currentState())
                }

                override fun onLost(network: Network) {
                    setState(currentState())
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    setState(currentState())
                }
            }

        init {
            setState(currentState())
        }

        override fun onStart(owner: LifecycleOwner) {
            if (registered) return
            // Re-seed because callbacks aren't guaranteed to fire for the
            // already-current network on registration.
            setState(currentState())
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
