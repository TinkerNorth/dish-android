// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.net.DishProtocol
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

    @Test
    fun `a never-probed satellite shows no compat verdict`() {
        val rows = satelliteRows(listOf(summary("satellite:mid:aa")), emptyList())
        assertEquals(DishProtocol.Compat.UNKNOWN, (rows[0] as SatelliteRow.Known).compat)
    }

    @Test
    fun `the compat chip follows the advertised protocol version`() {
        val features =
            mapOf(
                "old" to HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = DishProtocol.CURRENT - 1),
                "current" to HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = DishProtocol.CURRENT),
                "future" to HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = DishProtocol.CURRENT + 1),
            )
        val rows =
            satelliteRows(
                conns = listOf(summary("old"), summary("current"), summary("future")),
                discovered = emptyList(),
                features = features,
            )
        val compats = rows.filterIsInstance<SatelliteRow.Known>().associate { it.summary.id to it.compat }
        assertEquals(DishProtocol.Compat.SATELLITE_UPDATE_AVAILABLE, compats["old"])
        assertEquals(DishProtocol.Compat.CURRENT, compats["current"])
        assertEquals(DishProtocol.Compat.APP_UPDATE_REQUIRED, compats["future"])
    }

    @Test
    fun `a default host feature entry without a version read stays unknown`() {
        val rows =
            satelliteRows(
                conns = listOf(summary("satellite:mid:aa")),
                discovered = emptyList(),
                features = mapOf("satellite:mid:aa" to HostFeatureSet.SATELLITE_DEFAULT),
            )
        assertEquals(DishProtocol.Compat.UNKNOWN, (rows[0] as SatelliteRow.Known).compat)
    }
}
