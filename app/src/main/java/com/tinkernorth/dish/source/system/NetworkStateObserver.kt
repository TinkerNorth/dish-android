// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class NetworkState {
    NONE,
    CELLULAR,
    WIFI,
}

@Singleton
class NetworkStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AbstractStateSource<NetworkState>(NetworkState.NONE) {
        private val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Volatile private var registered = false

        private val _wifiDrops = MutableStateFlow(0)
        val wifiDrops: StateFlow<Int> = _wifiDrops.asStateFlow()

        private fun publish(next: NetworkState) {
            _wifiDrops.value = wifiDropsAfter(state.value, next, _wifiDrops.value)
            setState(next)
        }

        private inner class NetworkStateCallback : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(currentState())
            }

            override fun onLost(network: Network) {
                publish(currentState())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                publish(currentState())
            }
        }

        private val callback = NetworkStateCallback()

        init {
            publish(currentState())
        }

        override fun onStart(owner: LifecycleOwner) {
            if (registered) return
            // Callbacks aren't guaranteed to fire for the already-current network on registration.
            publish(currentState())
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
            return networkStateOf(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }
    }

// What the active network reads as for the LAN banners: ethernet is as good as Wi-Fi, and a
// network without internet is no network.
internal fun networkStateOf(
    hasInternet: Boolean,
    wifi: Boolean,
    ethernet: Boolean,
    cellular: Boolean,
): NetworkState =
    when {
        !hasInternet -> NetworkState.NONE
        wifi -> NetworkState.WIFI
        ethernet -> NetworkState.WIFI
        cellular -> NetworkState.CELLULAR
        else -> NetworkState.NONE
    }

// The drop counter moves only on the edge that leaves Wi-Fi.
internal fun wifiDropsAfter(
    prev: NetworkState,
    next: NetworkState,
    drops: Int,
): Int {
    val leftWifi = prev == NetworkState.WIFI && next != NetworkState.WIFI
    return if (leftWifi) drops + 1 else drops
}
