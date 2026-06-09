// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryGatewayTest {
    private fun server(
        name: String,
        ip: String,
        udp: Int = 9876,
    ) = DiscoveredServer(name = name, ip = ip, udpPort = udp)

    @Test
    fun broadcastOnlyServerIsTaggedBroadcast() {
        val merged =
            DiscoveryGateway.mergeDiscovered(
                broadcast = listOf(server("A", "10.0.0.1")),
                mdns = emptyList(),
            )
        assertEquals(1, merged.size)
        assertEquals(DiscoverySource.BROADCAST, merged.first().source)
    }

    @Test
    fun mdnsOnlyServerIsTaggedMdns() {
        val merged =
            DiscoveryGateway.mergeDiscovered(
                broadcast = emptyList(),
                mdns = listOf(server("B", "10.0.0.2")),
            )
        assertEquals(1, merged.size)
        assertEquals(DiscoverySource.MDNS, merged.first().source)
    }

    @Test
    fun serverHeardOnBothPathsIsTaggedBoth() {
        val merged =
            DiscoveryGateway.mergeDiscovered(
                broadcast = listOf(server("Sat", "10.0.0.9")),
                mdns = listOf(server("Sat", "10.0.0.9")),
            )
        assertEquals(1, merged.size)
        assertEquals(DiscoverySource.BOTH, merged.first().source)
    }

    @Test
    fun distinctServersFromEachPathAreKept() {
        val merged =
            DiscoveryGateway.mergeDiscovered(
                broadcast = listOf(server("Alpha", "10.0.0.1")),
                mdns = listOf(server("Bravo", "10.0.0.2")),
            )
        assertEquals(2, merged.size)
        assertEquals(DiscoverySource.BROADCAST, merged.first { it.name == "Alpha" }.source)
        assertEquals(DiscoverySource.MDNS, merged.first { it.name == "Bravo" }.source)
    }

    @Test
    fun samePairIpDifferentPortAreDistinctEntries() {
        val merged =
            DiscoveryGateway.mergeDiscovered(
                broadcast = listOf(server("One", "10.0.0.1", udp = 9876)),
                mdns = listOf(server("Two", "10.0.0.1", udp = 9900)),
            )
        assertEquals(2, merged.size)
    }

    @Test
    fun resultIsSortedByName() {
        val merged =
            DiscoveryGateway.mergeDiscovered(
                broadcast = listOf(server("Zulu", "10.0.0.3"), server("Alpha", "10.0.0.1")),
                mdns = listOf(server("Mike", "10.0.0.2")),
            )
        assertEquals(listOf("Alpha", "Mike", "Zulu"), merged.map { it.name })
    }

    @Test
    fun emptyInputsYieldEmptyResult() {
        assertTrue(
            DiscoveryGateway.mergeDiscovered(emptyList(), emptyList()).isEmpty(),
        )
    }

    @Test
    fun discoverySourceLabelResourcesAreSetAndDistinct() {
        val labels =
            listOf(
                DiscoverySource.BROADCAST.labelRes,
                DiscoverySource.MDNS.labelRes,
                DiscoverySource.BOTH.labelRes,
            )
        assertTrue("a discovery-source label resource is unset", labels.all { it != 0 })
        assertEquals("each discovery source needs its own label", labels.size, labels.toSet().size)
    }

    @Test
    fun pinIdFallsBackToHostWhenCallerPassesNoSatelliteId() {
        assertEquals("10.0.0.7", DiscoveryGateway.pinId(satelliteId = "", ip = "10.0.0.7"))
    }

    @Test
    fun pinIdPrefersExplicitSatelliteIdOverHost() {
        assertEquals(
            "satellite:mid:abc",
            DiscoveryGateway.pinId(satelliteId = "satellite:mid:abc", ip = "10.0.0.7"),
        )
    }
}
