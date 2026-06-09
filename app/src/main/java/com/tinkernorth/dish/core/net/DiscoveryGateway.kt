// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import android.util.Log
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import com.tinkernorth.dish.core.model.stableKey
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.source.connection.MdnsDiscovery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoveryGateway
    @Inject
    constructor(
        private val mdns: MdnsDiscovery,
        private val pins: SatellitePinRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        // Guards ONLY the native+mDNS discovery single-flight. Per-host HTTP is deliberately
        // left off this lock: a hung satellite must not block HTTP to a healthy one.
        private val discoveryMutex = Mutex()

        suspend fun discoverServers(
            port: Int,
            timeoutMs: Int,
        ): List<DiscoveredServer> =
            withContext(ioDispatcher) {
                discoveryMutex.withLock {
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

        // satelliteId is an optional trailing param (constant "" default, resolved to the host
        // in-body) so existing positional callers stay source-compatible. The cert pin protects
        // "the box at this address", which is the right key for TLS pinning on a CA-less LAN.
        suspend fun pair(
            ip: String,
            port: Int,
            deviceId: String,
            deviceName: String,
            pin: String,
            clientPin: String = "",
            satelliteId: String = "",
        ): String =
            withContext(ioDispatcher) {
                SatelliteHttpClient.pair(ip, port, deviceId, deviceName, pin, pinId(satelliteId, ip), pins, clientPin)
            }

        suspend fun pairStatus(
            ip: String,
            port: Int,
            deviceId: String,
            satelliteId: String = "",
        ): String =
            withContext(ioDispatcher) {
                SatelliteHttpClient.pairStatus(ip, port, deviceId, pinId(satelliteId, ip), pins)
            }

        suspend fun connect(
            ip: String,
            port: Int,
            deviceId: String,
            satelliteId: String = "",
        ): String =
            withContext(ioDispatcher) {
                SatelliteHttpClient.connect(ip, port, deviceId, pinId(satelliteId, ip), pins)
            }

        suspend fun disconnect(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
            satelliteId: String = "",
        ): String =
            withContext(ioDispatcher) {
                SatelliteHttpClient.disconnect(ip, port, connectionId, deviceId, pinId(satelliteId, ip), pins)
            }

        suspend fun setTouchpadMode(
            ip: String,
            port: Int,
            deviceId: String,
            mode: String,
            satelliteId: String = "",
        ): String =
            withContext(ioDispatcher) {
                SatelliteHttpClient.setTouchpadMode(ip, port, deviceId, mode, pinId(satelliteId, ip), pins)
            }

        companion object {
            private const val TAG = "DiscoveryGateway"

            // Empty satelliteId means "caller didn't override": key the pin on the host.
            internal fun pinId(
                satelliteId: String,
                ip: String,
            ): String = satelliteId.ifEmpty { ip }

            internal fun mergeDiscovered(
                broadcast: List<DiscoveredServer>,
                mdns: List<DiscoveredServer>,
            ): List<DiscoveredServer> {
                val byKey = LinkedHashMap<String, DiscoveredServer>()
                for (server in broadcast) {
                    byKey[server.stableKey] =
                        server.copy(source = DiscoverySource.BROADCAST)
                }
                for (server in mdns) {
                    // Same physical satellite heard on both paths collapses to
                    // one BOTH-tagged row when their stable ids match.
                    val key = server.stableKey
                    val source =
                        if (byKey.containsKey(key)) DiscoverySource.BOTH else DiscoverySource.MDNS
                    byKey[key] = server.copy(source = source)
                }
                return byKey.values.sortedBy { it.name }
            }
        }
    }
