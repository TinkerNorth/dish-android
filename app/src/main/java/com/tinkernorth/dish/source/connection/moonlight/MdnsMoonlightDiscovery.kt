// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.source.connection.ChannelDiscoveryListener
import com.tinkernorth.dish.source.connection.hostAddress
import com.tinkernorth.dish.source.connection.resolveNsdService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers Moonlight hosts over mDNS (`_nvstream._tcp`). Mirrors the satellite
 * [com.tinkernorth.dish.source.connection.MdnsDiscovery] plumbing exactly:
 * NsdManager discovery serialised through a channel, a multicast lock held for
 * the scan, and resolveService per service. Manual entry is the fallback (the
 * connection manager builds a [MoonlightHost] from a typed address).
 */
@Singleton
class MdnsMoonlightDiscovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun discover(timeoutMs: Int): List<MoonlightHost> =
            withContext(ioDispatcher) {
                val nsd =
                    context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                        ?: return@withContext emptyList()
                val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
                val listener = ChannelDiscoveryListener(TAG, found)
                val multicastLock = acquireMulticastLock()
                try {
                    try {
                        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "discoverServices rejected: ${e.message}")
                        return@withContext emptyList()
                    }
                    val results = LinkedHashMap<String, MoonlightHost>()
                    try {
                        withTimeoutOrNull(timeoutMs.toLong()) {
                            for (info in found) {
                                val host = resolveOne(nsd, info)
                                if (host != null) results[host.id] = host
                            }
                        }
                    } finally {
                        runCatching { nsd.stopServiceDiscovery(listener) }
                        found.close()
                    }
                    results.values.toList()
                } finally {
                    multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
                }
            }

        private fun acquireMulticastLock(): WifiManager.MulticastLock? {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            return runCatching {
                wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }

        private suspend fun resolveOne(
            nsd: NsdManager,
            info: NsdServiceInfo,
        ): MoonlightHost? = resolveNsdService(nsd, info)?.let(::toHost)

        private fun toHost(info: NsdServiceInfo): MoonlightHost? =
            mdnsServiceToHost(info.serviceName.orEmpty(), hostAddress(info), info.attributes.orEmpty())

        private companion object {
            const val TAG = "MdnsMoonlightDiscovery"

            // Moonlight advertises the HTTP server as _nvstream._tcp (index.adoc / rtsp.adoc).
            const val SERVICE_TYPE = "_nvstream._tcp."
            const val MULTICAST_LOCK_TAG = "Dish::MoonlightDiscovery"
        }
    }

/** Pure builder so the TXT/name mapping is unit-testable without NsdManager. */
internal fun mdnsServiceToHost(
    serviceName: String,
    hostAddress: String?,
    txt: Map<String, ByteArray?>,
): MoonlightHost? {
    val ip = hostAddress ?: return null
    val uniqueId = txt["uniqueid"]?.let { String(it).trim() }.orEmpty()
    return MoonlightHost(
        name = serviceName.ifEmpty { ip },
        address = ip,
        uniqueId = uniqueId,
    )
}
