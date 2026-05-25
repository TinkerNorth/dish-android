// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionStore
    @Inject
    constructor(
        val satellites: RememberedSatelliteRepository,
        val bt: RememberedBtRepository,
        val satelliteKeys: SatelliteSharedKeyRepository,
    ) {
        fun remembered(): List<RememberedSatellite> = satellites.all()

        fun rememberSatellite(server: DiscoveredServer) {
            satellites.put(
                RememberedSatellite(
                    id = SatelliteConnection.idFor(server),
                    name = server.name,
                    ip = server.ip,
                    udpPort = server.udpPort,
                    pairPort = server.pairPort,
                    httpPort = server.httpPort,
                ),
            )
        }

        fun forgetSatellite(id: String) {
            // Key first: a crash between removes leaves a re-pairable satellite, not an orphan key.
            satelliteKeys.remove(id)
            satellites.remove(id)
        }

        fun satelliteSharedKey(id: String): String? = satelliteKeys.get(id)

        fun setSatelliteSharedKey(
            id: String,
            keyHex: String,
        ) {
            satelliteKeys.put(id, keyHex)
        }

        fun forgetSatelliteSharedKey(id: String) {
            satelliteKeys.remove(id)
        }

        fun rememberedBt(): List<RememberedBt> = bt.all()

        fun rememberBt(entry: RememberedBt) {
            bt.put(entry)
        }

        fun forgetBt(id: String) {
            bt.remove(id)
        }
    }

@Serializable
data class RememberedSatellite(
    val id: String,
    val name: String,
    val ip: String,
    val udpPort: Int,
    val pairPort: Int,
    val httpPort: Int,
) {
    fun toDiscovered(): DiscoveredServer =
        DiscoveredServer(
            name = name,
            ip = ip,
            udpPort = udpPort,
            pairPort = pairPort,
            httpPort = httpPort,
        )
}

@Serializable
data class RememberedBt(
    val id: String,
    val name: String,
    val mac: String,
    val profileName: String,
)
