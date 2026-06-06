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
            val id = SatelliteConnection.idFor(server)
            if (server.machineId.isNotBlank()) reconcileLegacyGhosts(server, id)
            satellites.put(
                RememberedSatellite(
                    id = id,
                    name = server.name,
                    ip = server.ip,
                    udpPort = server.udpPort,
                    pairPort = server.pairPort,
                    httpPort = server.httpPort,
                    machineId = server.machineId,
                ),
            )
        }

        /**
         * A satellite that now advertises a stable [DiscoveredServer.machineId]
         * used to be remembered under `satellite:<ip>:<udpPort>`, and may have
         * left a ghost row at every old IP it ever held (the duplicate-connection
         * bug). Collapse them into the new stable id: adopt the shared key off
         * the current-ip:port legacy entry so the user isn't forced to re-pair
         * just because the id scheme changed, then forget every machineId-less
         * entry for the same box — the one at this exact ip:port plus any sharing
         * this name (its ghosts stranded at dead IPs). Conservative by design:
         * it only ever removes legacy (machineId-blank) rows, never another
         * stably-identified satellite.
         */
        private fun reconcileLegacyGhosts(
            server: DiscoveredServer,
            id: String,
        ) {
            val legacyId = "satellite:${server.ip}:${server.udpPort}"
            if (satelliteKeys.get(id) == null) {
                satelliteKeys.get(legacyId)?.let { satelliteKeys.put(id, it) }
            }
            for (entry in satellites.all()) {
                if (entry.id == id) continue
                val isGhostOfThisBox =
                    entry.machineId.isBlank() &&
                        (entry.id == legacyId || entry.name == server.name)
                if (isGhostOfThisBox) forgetSatellite(entry.id)
            }
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
    // Stable per-install id; "" for entries remembered before machineId existed
    // (default keeps old persisted JSON deserialising). Lets reconciliation tell
    // a stably-identified satellite apart from a legacy ghost.
    val machineId: String = "",
) {
    fun toDiscovered(): DiscoveredServer =
        DiscoveredServer(
            name = name,
            ip = ip,
            udpPort = udpPort,
            pairPort = pairPort,
            httpPort = httpPort,
            machineId = machineId,
        )
}

@Serializable
data class RememberedBt(
    val id: String,
    val name: String,
    val mac: String,
    val profileName: String,
)
