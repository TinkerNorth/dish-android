// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import kotlinx.coroutines.flow.StateFlow
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
        val satellitePins: SatellitePinRepository,
    ) {
        // Observable projections so the connections composer derives purely from flows, with no
        // out-of-band prefs read that a remember/forget could leave stale.
        val rememberedSatellitesFlow: StateFlow<List<RememberedSatellite>> = satellites.entries
        val rememberedBtFlow: StateFlow<List<RememberedBt>> = bt.entries

        fun remembered(): List<RememberedSatellite> = satellites.all()

        // Identity is machineId-only (satellite docs/contract.md §Identity):
        // one physical receiver is exactly one remembered row. idFor still has
        // to mint an ip:port id while the machineId is unknown (manual add), so
        // the upsert itself keeps the invariant across an identity upgrade:
        // there is no separate reconciliation pass to run, or forget to run.
        fun rememberSatellite(server: DiscoveredServer) {
            if (server.machineId.isBlank() && refreshKnownBox(server)) return
            val id = SatelliteConnection.idFor(server)
            if (server.machineId.isNotBlank()) collapseLegacyGhosts(server, id)
            migratePinOnAddressChange(satellites.get(id)?.ip, server.ip)
            val row =
                RememberedSatellite(
                    id = id,
                    name = server.name,
                    ip = server.ip,
                    udpPort = server.udpPort,
                    pairPort = server.pairPort,
                    httpPort = server.httpPort,
                    machineId = server.machineId,
                )
            if (satellites.get(id) != row) satellites.put(row)
        }

        fun refreshFromDiscovery(discovered: List<DiscoveredServer>) {
            val rows = satellites.all()
            val knownIds = rows.mapTo(mutableSetOf()) { it.id }
            discovered
                .filter { it.machineId.isNotBlank() }
                .filter { server ->
                    SatelliteConnection.idFor(server) in knownIds ||
                        rows.any { it.machineId.isBlank() && it.ip == server.ip && it.udpPort == server.udpPort }
                }.forEach(::rememberSatellite)
        }

        // The box may already be known under its stable id: refresh that row
        // instead of minting an ip:port ghost beside it.
        private fun refreshKnownBox(server: DiscoveredServer): Boolean {
            val stable =
                satellites.all().firstOrNull {
                    it.machineId.isNotBlank() && it.ip == server.ip && it.udpPort == server.udpPort
                } ?: return false
            satellites.put(
                stable.copy(
                    name = server.name,
                    pairPort = server.pairPort,
                    httpPort = server.httpPort,
                ),
            )
            return true
        }

        // The box just gained a stable id: collapse the legacy ip:port row it
        // leaves behind, carrying its pairing key forward so the upgrade never
        // forces a re-pair.
        private fun collapseLegacyGhosts(
            server: DiscoveredServer,
            id: String,
        ) {
            satellites
                .all()
                .filter { it.machineId.isBlank() && it.ip == server.ip && it.udpPort == server.udpPort }
                .forEach { ghost ->
                    if (satelliteKeys.get(id) == null) {
                        satelliteKeys.get(ghost.id)?.let { satelliteKeys.put(id, it) }
                    }
                    satelliteKeys.remove(ghost.id)
                    satellites.remove(ghost.id)
                }
        }

        private fun migratePinOnAddressChange(
            oldIp: String?,
            newIp: String,
        ) {
            if (oldIp == null || oldIp == newIp) return
            if (satellitePins.pinnedFingerprint(newIp) == null) {
                satellitePins.pinnedFingerprint(oldIp)?.let { satellitePins.pin(newIp, it) }
            }
            satellitePins.forget(oldIp)
        }

        fun forgetSatellite(id: String) {
            satellites.get(id)?.ip?.let(satellitePins::forget)
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
