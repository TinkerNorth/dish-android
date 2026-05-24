// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net

import android.util.Log
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.source.connection.MdnsDiscovery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository handling server discovery and pairing via SatelliteNative.
 */
@Singleton
class DiscoveryRepository
    @Inject
    constructor(
        private val mdns: MdnsDiscovery,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutex = Mutex()

        /**
         * Discover satellites via two paths in parallel and merge by
         * `ip:udpPort`: the legacy UDP broadcast beacon (native
         * [SatelliteNative.discoverServers]) and mDNS / Bonjour
         * ([MdnsDiscovery]). mDNS reaches subnets that drop broadcast; the
         * beacon stays as the fallback for satellites that predate the mDNS
         * responder.
         */
        suspend fun discoverServers(
            port: Int,
            timeoutMs: Int,
        ): List<DiscoveredServer> =
            withContext(ioDispatcher) {
                mutex.withLock {
                    coroutineScope {
                        val broadcast =
                            async { parseServers(SatelliteNative.discoverServers(port, timeoutMs)) }
                        val viaMdns = async { mdns.discover(timeoutMs) }
                        val broadcastList = broadcast.await()
                        val mdnsList = viaMdns.await()
                        val merged = mergeDiscovered(broadcastList, mdnsList)
                        Log.i(
                            TAG,
                            "discovery scan: broadcast=${broadcastList.size} " +
                                "mdns=${mdnsList.size} merged=${merged.size}",
                        )
                        merged
                    }
                }
            }

        /**
         * PIN-pairing handshake — POST /api/pair on the satellite's HTTPS
         * client server (port 9443). The request/response JSON is unchanged
         * from the old raw-TCP pairing protocol; only the transport differs.
         */
        suspend fun pair(
            ip: String,
            port: Int,
            deviceId: String,
            deviceName: String,
            pin: String,
        ): String =
            withContext(ioDispatcher) {
                mutex.withLock {
                    SatelliteHttpClient.pair(ip, port, deviceId, deviceName, pin)
                }
            }

        /** POST /api/connections over HTTPS. */
        suspend fun connect(
            ip: String,
            port: Int,
            deviceId: String,
        ): String =
            withContext(ioDispatcher) {
                mutex.withLock {
                    SatelliteHttpClient.connect(ip, port, deviceId)
                }
            }

        /** DELETE /api/connections/<id> over HTTPS. */
        suspend fun disconnect(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
        ): String =
            withContext(ioDispatcher) {
                mutex.withLock {
                    SatelliteHttpClient.disconnect(ip, port, connectionId, deviceId)
                }
            }

        /**
         * POST /api/devices/touchpad-mode over HTTPS. Server hot-applies to the
         * live session and persists per-device so a re-connect reuses the same
         * routing. The raw JSON reply is returned so the caller can decode
         * `{"ok":true,"hotApplied":bool}` vs `{"error":"…"}` and surface a
         * server-rejected mode (e.g. picking `ds4` against a macOS receiver
         * that only advertises `off`) to the UI.
         */
        suspend fun setTouchpadMode(
            ip: String,
            port: Int,
            deviceId: String,
            mode: String,
        ): String =
            withContext(ioDispatcher) {
                mutex.withLock {
                    SatelliteHttpClient.setTouchpadMode(ip, port, deviceId, mode)
                }
            }

        companion object {
            private const val TAG = "DiscoveryRepository"

            /**
             * Merge the two discovery paths by `ip:udpPort`, tagging each
             * server's [DiscoveredServer.source]. A server heard on both paths
             * becomes [DiscoverySource.BOTH]; otherwise it carries the path
             * that surfaced it. Result is name-sorted. Pure + internal so it
             * can be unit-tested without sockets.
             */
            internal fun mergeDiscovered(
                broadcast: List<DiscoveredServer>,
                mdns: List<DiscoveredServer>,
            ): List<DiscoveredServer> {
                val byKey = LinkedHashMap<String, DiscoveredServer>()
                for (server in broadcast) {
                    byKey["${server.ip}:${server.udpPort}"] =
                        server.copy(source = DiscoverySource.BROADCAST)
                }
                for (server in mdns) {
                    val key = "${server.ip}:${server.udpPort}"
                    val source =
                        if (byKey.containsKey(key)) DiscoverySource.BOTH else DiscoverySource.MDNS
                    byKey[key] = server.copy(source = source)
                }
                return byKey.values.sortedBy { it.name }
            }
        }
    }
