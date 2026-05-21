// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade combining the three durable repositories used by the connections subsystem:
 *
 *   - [satellites] — [RememberedSatelliteRepository] (remembered Satellite hosts list)
 *   - [bt] — [RememberedBtRepository] (remembered Bluetooth HID hosts list)
 *   - [satelliteKeys] — [SatelliteSharedKeyRepository] (per-satellite pair-derived shared keys)
 *
 * **Why a facade and not just inject the three repos directly?** Existing callers
 * (`SatelliteConnectionManager`, `ConnectionHub`, `BluetoothBondMonitor`,
 * `ConnectionsActivity`) speak in terms of "the store" — a single object with the
 * three concerns folded in. New code should prefer injecting the underlying repositories
 * directly; the facade keeps the migration cheap and surfaces the underlying
 * [com.tinkernorth.dish.architecture.interfaces.Repository] implementations as public properties so call
 * sites can drop down to the typed API when convenient.
 */
@Singleton
class ConnectionStore
    @Inject
    constructor(
        val satellites: RememberedSatelliteRepository,
        val bt: RememberedBtRepository,
        val satelliteKeys: SatelliteSharedKeyRepository,
    ) {
        // ── Satellites ────────────────────────────────────────────────────────

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
            // Key first, so a crash between the two removes leaves the user
            // with a remembered satellite that needs re-pairing (recoverable
            // by the existing pair flow) rather than an orphaned shared key
            // with no remembered satellite (a silent leak that accumulates).
            satelliteKeys.remove(id)
            satellites.remove(id)
        }

        // ── Per-satellite shared keys ─────────────────────────────────────────

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

        // ── Bluetooth ─────────────────────────────────────────────────────────

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
    /** Stable id derived from MAC. */
    val id: String,
    val name: String,
    val mac: String,
    val profileName: String,
)
