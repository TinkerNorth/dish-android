// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.model.DiscoverySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Discovers Satellite servers advertised over mDNS / Bonjour as the
 * `_satellite._udp.` service type — the modern discovery path that works on
 * subnets where the legacy UDP broadcast beacon is dropped (Wi-Fi client
 * isolation, IoT VLANs).
 *
 * Pairs with the satellite-side responder in
 * `satellite/src/net/mdns_responder.cpp`. Android has a first-class mDNS
 * stack, so this uses the platform [NsdManager] rather than a hand-rolled
 * multicast socket — `NsdManager` shares the system resolver and needs no
 * multicast lock for browsing.
 */
@Singleton
class MdnsDiscovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Browse for [timeoutMs], resolving every advertised satellite to a
         * [DiscoveredServer]. Never throws — a missing NSD service or an empty
         * LAN just yields an empty list.
         */
        suspend fun discover(timeoutMs: Int): List<DiscoveredServer> =
            withContext(Dispatchers.IO) {
                val nsd =
                    context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                        ?: return@withContext emptyList()

                // Found services arrive on NsdManager's callback thread; the
                // channel hands them to this coroutine for serial resolution
                // (NsdManager.resolveService is single-flight on older Android).
                val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
                val listener = discoveryListener(found)

                try {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "discoverServices rejected: ${e.message}")
                    return@withContext emptyList()
                }

                val results = LinkedHashMap<String, DiscoveredServer>()
                withTimeoutOrNull(timeoutMs.toLong()) {
                    for (info in found) {
                        val server = resolveOne(nsd, info)
                        if (server != null) {
                            results["${server.ip}:${server.udpPort}"] = server
                        }
                    }
                }

                runCatching { nsd.stopServiceDiscovery(listener) }
                found.close()
                results.values.toList()
            }

        private fun discoveryListener(found: Channel<NsdServiceInfo>): NsdManager.DiscoveryListener =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    found.trySend(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "discovery start failed: $errorCode")
                    found.close()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit
            }

        /** Resolve one found service to a [DiscoveredServer], or null on failure. */
        private suspend fun resolveOne(
            nsd: NsdManager,
            info: NsdServiceInfo,
        ): DiscoveredServer? =
            suspendCancellableCoroutine { cont ->
                val listener =
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            si: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceResolved(si: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(toServer(si))
                        }
                    }
                try {
                    nsd.resolveService(info, listener)
                } catch (e: IllegalArgumentException) {
                    // A resolve is already in flight — skip this one rather
                    // than crash. The for-loop serialises calls, but a stray
                    // platform race can still trip this on some OEM builds.
                    if (cont.isActive) cont.resume(null)
                }
            }

        /** Build a [DiscoveredServer] from a resolved [NsdServiceInfo]. */
        private fun toServer(info: NsdServiceInfo): DiscoveredServer? {
            val ip = info.host?.hostAddress ?: return null
            // Prefer the TXT ports (canonical); fall back to the SRV port for
            // udp, and to the protocol defaults for pair / http.
            val txt = info.attributes.orEmpty()

            fun txtInt(key: String): Int? = txt[key]?.let { String(it).trim().toIntOrNull() }
            return DiscoveredServer(
                name = info.serviceName.ifEmpty { ip },
                ip = ip,
                udpPort = txtInt("udp") ?: info.port.takeIf { it > 0 } ?: DEFAULT_UDP,
                pairPort = txtInt("pair") ?: DEFAULT_PAIR,
                httpPort = txtInt("http") ?: DEFAULT_HTTP,
                source = DiscoverySource.MDNS,
            )
        }

        private companion object {
            const val TAG = "MdnsDiscovery"

            // NsdManager wants the bare DNS-SD type with a trailing dot.
            const val SERVICE_TYPE = "_satellite._udp."

            const val DEFAULT_UDP = 9876
            const val DEFAULT_PAIR = 9878
            const val DEFAULT_HTTP = 9877
        }
    }
