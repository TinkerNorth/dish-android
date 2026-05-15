// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.repository

import com.tinkernorth.dish.data.model.DiscoveredServer
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
                        val merged = LinkedHashMap<String, DiscoveredServer>()
                        for (server in broadcast.await() + viaMdns.await()) {
                            merged["${server.ip}:${server.udpPort}"] = server
                        }
                        merged.values.toList()
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
    }
