// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.SatelliteConnection

data class ConnectionsUiState(
    val satelliteRows: List<SatelliteRow>,
    val bluetoothSummaries: List<ConnectionSummary>,
    val rememberedBtIds: Set<String>,
    val scanning: Boolean,
    val lastScanAtMs: Long?,
) {
    companion object {
        val Empty =
            ConnectionsUiState(
                satelliteRows = emptyList(),
                bluetoothSummaries = emptyList(),
                rememberedBtIds = emptySet(),
                scanning = false,
                lastScanAtMs = null,
            )
    }
}

// Known connections first, then discovered servers not already known under their stable id.
fun satelliteRows(
    conns: List<ConnectionSummary>,
    discovered: List<DiscoveredServer>,
): List<SatelliteRow> {
    val satConns = conns.filter { it.kind == ConnectionKind.SATELLITE }
    val knownIds = satConns.mapTo(mutableSetOf()) { it.id }
    return buildList {
        satConns.forEach { add(SatelliteRow.Known(it)) }
        discovered.forEach { server ->
            if (SatelliteConnection.idFor(server) !in knownIds) add(SatelliteRow.Discovered(server))
        }
    }
}

fun bluetoothSummaries(conns: List<ConnectionSummary>): List<ConnectionSummary> = conns.filter { it.kind == ConnectionKind.BLUETOOTH }
