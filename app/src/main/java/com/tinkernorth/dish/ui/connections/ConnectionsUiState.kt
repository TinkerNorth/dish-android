// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.net.DishProtocol
import com.tinkernorth.dish.source.connection.SatelliteConnection

data class ConnectionsUiState(
    val satelliteRows: List<SatelliteRow>,
    val bluetoothSummaries: List<ConnectionSummary>,
    val moonlightRows: List<MoonlightRow>,
    val rememberedBtIds: Set<String>,
    val scanning: Boolean,
    val moonlightScanning: Boolean,
    val lastScanAtMs: Long?,
) {
    companion object {
        val Empty =
            ConnectionsUiState(
                satelliteRows = emptyList(),
                bluetoothSummaries = emptyList(),
                moonlightRows = emptyList(),
                rememberedBtIds = emptySet(),
                scanning = false,
                moonlightScanning = false,
                lastScanAtMs = null,
            )
    }
}

// Known connections first, then discovered servers not already known under their stable id.
// The compat chip reads the advertised (or negotiated) protocol version; satellites the
// client never probed stay UNKNOWN and show nothing.
fun satelliteRows(
    conns: List<ConnectionSummary>,
    discovered: List<DiscoveredServer>,
    features: Map<String, HostFeatureSet> = emptyMap(),
): List<SatelliteRow> {
    val satConns = conns.filter { it.kind == ConnectionKind.SATELLITE }
    val knownIds = satConns.mapTo(mutableSetOf()) { it.id }
    return buildList {
        satConns.forEach {
            val version = features[it.id]?.protocolVersion?.takeIf { v -> v > 0 }
            add(SatelliteRow.Known(it, DishProtocol.compatFor(version)))
        }
        discovered.forEach { server ->
            if (SatelliteConnection.idFor(server) !in knownIds) add(SatelliteRow.Discovered(server))
        }
    }
}

fun bluetoothSummaries(conns: List<ConnectionSummary>): List<ConnectionSummary> = conns.filter { it.kind == ConnectionKind.BLUETOOTH }
