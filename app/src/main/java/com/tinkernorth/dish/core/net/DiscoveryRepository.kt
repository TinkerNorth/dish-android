// SPDX-License-Identifier: LGPL-3.0-or-later

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

@Singleton
class DiscoveryRepository
    @Inject
    constructor(
        private val mdns: MdnsDiscovery,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutex = Mutex()

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
