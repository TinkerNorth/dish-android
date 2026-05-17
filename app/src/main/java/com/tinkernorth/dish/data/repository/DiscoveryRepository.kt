// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.repository

import android.util.Log
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.model.DiscoverySource
import com.tinkernorth.dish.data.network.MdnsDiscovery
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.data.network.parseServers
import kotlinx.coroutines.Dispatchers
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
            withContext(Dispatchers.IO) {
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

        suspend fun pair(
            ip: String,
            port: Int,
            deviceId: String,
            deviceName: String,
            pin: String,
        ): String =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    SatelliteNative.pair(ip, port, deviceId, deviceName, pin)
                }
            }

        suspend fun connect(
            ip: String,
            port: Int,
            deviceId: String,
        ): String =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    SatelliteNative.httpConnect(ip, port, deviceId)
                }
            }

        suspend fun disconnect(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
        ): String =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    SatelliteNative.httpDisconnect(ip, port, connectionId, deviceId)
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
