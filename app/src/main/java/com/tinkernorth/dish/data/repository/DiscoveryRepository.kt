package com.tinkernorth.dish.data.repository

import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.data.network.parseServers
import kotlinx.coroutines.Dispatchers
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
    constructor() {
        private val mutex = Mutex()

        suspend fun discoverServers(
            port: Int,
            timeoutMs: Int,
        ): List<DiscoveredServer> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val json = SatelliteNative.discoverServers(port, timeoutMs)
                    parseServers(json)
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
