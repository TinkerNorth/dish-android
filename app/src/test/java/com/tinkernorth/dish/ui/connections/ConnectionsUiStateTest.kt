// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.DiscoveredServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionsUiStateTest {
    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = LinkState.Saved,
        boundSlotIds = emptyList(),
    )

    private fun server(machineId: String) =
        DiscoveredServer(name = machineId, ip = "10.0.0.9", udpPort = 9876, pairPort = 1, httpPort = 1, machineId = machineId)

    @Test
    fun `known satellites come first, then unknown discovered servers`() {
        val rows =
            satelliteRows(
                conns = listOf(summary("satellite:mid:aa")),
                discovered = listOf(server("bb")),
            )
        assertEquals(2, rows.size)
        assertEquals("satellite:mid:aa", (rows[0] as SatelliteRow.Known).summary.id)
        assertEquals("bb", (rows[1] as SatelliteRow.Discovered).server.machineId)
    }

    @Test
    fun `a discovered server already known under its stable id is not duplicated`() {
        val rows =
            satelliteRows(
                conns = listOf(summary("satellite:mid:aa")),
                discovered = listOf(server("aa")),
            )
        assertEquals(listOf<Class<*>>(SatelliteRow.Known::class.java), rows.map { it.javaClass })
    }

    @Test
    fun `bluetooth summaries never appear as satellite rows and vice versa`() {
        val conns = listOf(summary("satellite:mid:aa"), summary("bt:mac", kind = ConnectionKind.BLUETOOTH))
        assertEquals(1, satelliteRows(conns, emptyList()).size)
        assertEquals(listOf("bt:mac"), bluetoothSummaries(conns).map { it.id })
    }

    @Test
    fun `no connections and no discoveries produce no rows`() {
        assertEquals(emptyList<SatelliteRow>(), satelliteRows(emptyList(), emptyList()))
    }
}
