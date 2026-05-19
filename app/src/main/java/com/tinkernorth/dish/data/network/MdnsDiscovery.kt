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
                try {
                    withTimeoutOrNull(timeoutMs.toLong()) {
                        for (info in found) {
                            val server = resolveOne(nsd, info)
                            if (server != null) {
                                results["${server.ip}:${server.udpPort}"] = server
                            }
                        }
                    }
                } finally {
                    // Cleanup MUST run on cancellation, not just on timeout: a
                    // plain post-block teardown is skipped when the coroutine
                    // is cancelled (caller navigates away mid-browse), leaking
                    // the registered DiscoveryListener for the process' life.
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

        /**
         * Resolve one found service to a [DiscoveredServer], or null on
         * failure.
         *
         * `NsdManager.resolveService` is deprecated on API 34+ in favour of
         * `registerServiceInfoCallback`, but that replacement is API 34-only
         * and `minSdk` is 24 — so the deprecated call is still the correct
         * path for API 24–33. The `@Suppress` is deliberate.
         */
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
                    // A resolve is already in flight — skip this one rather
                    // than crash. The for-loop serialises calls, but a stray
                    // platform race can still trip this on some OEM builds.
                    if (cont.isActive) cont.resume(null)
                }
            }

        /**
         * Build a [DiscoveredServer] from a resolved [NsdServiceInfo].
         *
         * The framework-free mapping itself lives in [mdnsServiceToServer] so
         * the TXT-record parsing, SRV-vs-TXT port fallback, and default-port
         * logic can be unit-tested without an [NsdServiceInfo] (a framework
         * class that cannot be constructed in a JVM test).
         *
         * `NsdServiceInfo.host` is deprecated on API 34+ (`getHostAddresses()`)
         * but a single-address host is still correct for API 24–33; the
         * `@Suppress` is deliberate.
         */
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

            // NsdManager wants the bare DNS-SD type with a trailing dot.
            const val SERVICE_TYPE = "_satellite._udp."
        }
    }

// ── Pure mDNS → DiscoveredServer mapping ─────────────────────────────────────

/** Protocol default UDP port (`discPort` beacon advertises the real one). */
internal const val MDNS_DEFAULT_UDP = 9876

/**
 * Protocol default client-API port. Pairing (POST /api/pair) and the
 * connection API now share the satellite's single HTTPS/TLS client server on
 * port 9443. The `pair` TXT key still exists for backwards compatibility and
 * advertises this same port.
 */
internal const val MDNS_DEFAULT_PAIR = 9443

/** Protocol default HTTPS client-API port (connection API + pairing). */
internal const val MDNS_DEFAULT_HTTP = 9443

/**
 * Pure mapping of a resolved mDNS service to a [DiscoveredServer], lifted out
 * of [MdnsDiscovery.toServer] so it can be exercised in JVM unit tests without
 * an [android.net.nsd.NsdServiceInfo] (a framework class with no public
 * constructor — a roadmap deliverable left untested otherwise).
 *
 * Port resolution mirrors the satellite mDNS responder
 * (`satellite/src/net/mdns_responder.cpp`):
 *
 *  - **udp** — the canonical `udp` TXT record wins; otherwise the SRV record's
 *    port (when > 0); otherwise [MDNS_DEFAULT_UDP].
 *  - **pair / http** — the `pair` / `http` TXT record wins; otherwise the
 *    protocol defaults. The SRV port is *not* a fallback for these — it
 *    advertises the UDP service only.
 *
 * A service with no resolvable host address yields null — there is nothing to
 * connect to. The service name falls back to the IP when empty so the
 * connections list always has a label.
 *
 * @param serviceName the advertised instance name (may be empty)
 * @param hostAddress the resolved host IP, or null when resolution gave none
 * @param srvPort the SRV-record port; 0 / negative means "not advertised"
 * @param txt the TXT-record attribute map (values are raw bytes, as NSD gives)
 */
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
        source = DiscoverySource.MDNS,
    )
}

/**
 * Read a TXT-record value as an [Int], or null when the key is absent, the
 * value is not present, or it does not parse as an integer. NSD hands TXT
 * values back as raw bytes; they are decoded as UTF-8 and trimmed.
 */
internal fun mdnsTxtInt(
    txt: Map<String, ByteArray?>,
    key: String,
): Int? = txt[key]?.let { String(it).trim().toIntOrNull() }
