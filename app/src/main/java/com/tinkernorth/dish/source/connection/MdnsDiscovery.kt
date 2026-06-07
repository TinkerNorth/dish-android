// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import com.tinkernorth.dish.core.model.stableKey
import com.tinkernorth.dish.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MdnsDiscovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun discover(timeoutMs: Int): List<DiscoveredServer> =
            withContext(ioDispatcher) {
                val nsd =
                    context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                        ?: return@withContext emptyList()

                // Serialise via channel: NsdManager.resolveService is single-flight on older Android.
                val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
                val listener = discoveryListener(found)

                try {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "discoverServices rejected: ${e.message}")
                    return@withContext emptyList()
                }

                val results = LinkedHashMap<String, DiscoveredServer>()
                try {
                    withTimeoutOrNull(timeoutMs.toLong()) {
                        for (info in found) {
                            val server = resolveOne(nsd, info)
                            if (server != null) {
                                // Key on the stable id so a satellite that also
                                // answers the broadcast beacon merges to one row.
                                results[server.stableKey] = server
                            }
                        }
                    }
                } finally {
                    // MUST run on cancellation too — otherwise the DiscoveryListener leaks for the process' life.
                    runCatching { nsd.stopServiceDiscovery(listener) }
                    found.close()
                }
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

        // resolveService deprecated on API 34+ but the replacement is API 34-only; minSdk is 24.
        @Suppress("DEPRECATION")
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
                    // Some OEMs race past the for-loop's serialisation and reject as in-flight.
                    if (cont.isActive) cont.resume(null)
                }
            }

        // NsdServiceInfo.host deprecated on API 34+; single-address host still correct on API 24–33.
        @Suppress("DEPRECATION")
        private fun toServer(info: NsdServiceInfo): DiscoveredServer? =
            mdnsServiceToServer(
                serviceName = info.serviceName.orEmpty(),
                hostAddress = info.host?.hostAddress,
                srvPort = info.port,
                txt = info.attributes.orEmpty(),
            )

        private companion object {
            const val TAG = "MdnsDiscovery"
            const val SERVICE_TYPE = "_satellite._udp."
        }
    }

internal const val MDNS_DEFAULT_UDP = 9876
internal const val MDNS_DEFAULT_PAIR = 9443
internal const val MDNS_DEFAULT_HTTP = 9443

internal fun mdnsServiceToServer(
    serviceName: String,
    hostAddress: String?,
    srvPort: Int,
    txt: Map<String, ByteArray?>,
): DiscoveredServer? {
    val ip = hostAddress ?: return null
    return DiscoveredServer(
        name = serviceName.ifEmpty { ip },
        ip = ip,
        udpPort = mdnsTxtInt(txt, "udp") ?: srvPort.takeIf { it > 0 } ?: MDNS_DEFAULT_UDP,
        pairPort = mdnsTxtInt(txt, "pair") ?: MDNS_DEFAULT_PAIR,
        httpPort = mdnsTxtInt(txt, "http") ?: MDNS_DEFAULT_HTTP,
        machineId = mdnsTxtString(txt, "mid").orEmpty(),
        source = DiscoverySource.MDNS,
    )
}

internal fun mdnsTxtInt(
    txt: Map<String, ByteArray?>,
    key: String,
): Int? = txt[key]?.let { String(it).trim().toIntOrNull() }

internal fun mdnsTxtString(
    txt: Map<String, ByteArray?>,
    key: String,
): String? = txt[key]?.let { String(it).trim() }?.takeIf { it.isNotEmpty() }
