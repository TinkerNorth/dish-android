// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import android.util.Log
import com.tinkernorth.dish.core.jni.SessionNative
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import com.tinkernorth.dish.core.model.stableKey
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
class DiscoveryGateway
    @Inject
    constructor(
        private val mdns: MdnsDiscovery,
        private val http: SatelliteHttpClient,
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
                            async { parseServers(SessionNative.discoverServers(port, timeoutMs)) }
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
            protocolVersion: Int = DishProtocol.DISH_PROTOCOL_CURRENT,
        ): HttpReply =
            withContext(ioDispatcher) {
                http.pair(
                    ip,
                    port,
                    deviceId,
                    deviceName,
                    pin,
                    pinId(satelliteId, ip),
                    clientPin,
                    protocolVersion,
                )
            }

        suspend fun pairStatus(
            ip: String,
            port: Int,
            deviceId: String,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.pairStatus(ip, port, deviceId, pinId(satelliteId, ip))
            }

        suspend fun putSession(
            ip: String,
            port: Int,
            deviceId: String,
            deviceName: String,
            hmacProof: String,
            descriptorsJson: String,
            requestMouseControl: Boolean,
            protocolVersion: Int = DishProtocol.DISH_PROTOCOL_CURRENT,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.putSession(
                    ip,
                    port,
                    deviceId,
                    deviceName,
                    hmacProof,
                    descriptorsJson,
                    requestMouseControl,
                    protocolVersion,
                    pinId(satelliteId, ip),
                )
            }

        suspend fun getSession(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
            hmacProof: String,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.getSession(ip, port, connectionId, deviceId, hmacProof, pinId(satelliteId, ip))
            }

        suspend fun putController(
            ip: String,
            port: Int,
            connectionId: String,
            ctrlIdx: Int,
            deviceId: String,
            hmacProof: String,
            descriptorJson: String,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.putController(
                    ip,
                    port,
                    connectionId,
                    ctrlIdx,
                    deviceId,
                    hmacProof,
                    descriptorJson,
                    pinId(satelliteId, ip),
                )
            }

        suspend fun deleteController(
            ip: String,
            port: Int,
            connectionId: String,
            ctrlIdx: Int,
            deviceId: String,
            hmacProof: String,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.deleteController(
                    ip,
                    port,
                    connectionId,
                    ctrlIdx,
                    deviceId,
                    hmacProof,
                    pinId(satelliteId, ip),
                )
            }

        suspend fun disconnect(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
            hmacProof: String,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.disconnect(ip, port, connectionId, deviceId, hmacProof, pinId(satelliteId, ip))
            }

        suspend fun unpair(
            ip: String,
            port: Int,
            deviceId: String,
            hmacProof: String,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.unpair(ip, port, deviceId, hmacProof, pinId(satelliteId, ip))
            }

        suspend fun catalog(
            ip: String,
            port: Int,
            acceptLanguage: String,
            etag: String?,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.getCatalog(ip, port, acceptLanguage, etag, pinId(satelliteId, ip))
            }

        suspend fun serverCapabilities(
            ip: String,
            port: Int,
            satelliteId: String = "",
        ): HttpReply =
            withContext(ioDispatcher) {
                http.getServerCapabilities(ip, port, pinId(satelliteId, ip))
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
