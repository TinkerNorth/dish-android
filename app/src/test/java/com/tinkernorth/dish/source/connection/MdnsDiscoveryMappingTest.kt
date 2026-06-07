// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.model.DiscoverySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsDiscoveryMappingTest {
    private fun bytes(s: String): ByteArray = s.toByteArray()

    @Test
    fun `txtInt parses a numeric value`() {
        assertEquals(9876, mdnsTxtInt(mapOf("udp" to bytes("9876")), "udp"))
    }

    @Test
    fun `txtInt trims surrounding whitespace`() {
        assertEquals(9878, mdnsTxtInt(mapOf("pair" to bytes(" 9878 ")), "pair"))
    }

    @Test
    fun `txtInt returns null for a missing key`() {
        assertNull(mdnsTxtInt(mapOf("udp" to bytes("9876")), "http"))
    }

    @Test
    fun `txtInt returns null for a null value`() {
        assertNull(mdnsTxtInt(mapOf<String, ByteArray?>("udp" to null), "udp"))
    }

    @Test
    fun `txtInt returns null for a non-numeric value`() {
        assertNull(mdnsTxtInt(mapOf("udp" to bytes("not-a-port")), "udp"))
    }

    @Test
    fun `null host yields null - nothing to connect to`() {
        assertNull(
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = null,
                srvPort = 9876,
                txt = emptyMap(),
            ),
        )
    }

    @Test
    fun `resolved server is tagged MDNS`() {
        val s =
            mdnsServiceToServer("Sat", "10.0.0.5", 9876, emptyMap())
        assertEquals(DiscoverySource.MDNS, s!!.source)
    }

    @Test
    fun `empty service name falls back to the ip`() {
        val s = mdnsServiceToServer("", "10.0.0.7", 9876, emptyMap())
        assertEquals("10.0.0.7", s!!.name)
    }

    @Test
    fun `non-empty service name is kept`() {
        val s = mdnsServiceToServer("Living Room PC", "10.0.0.7", 9876, emptyMap())
        assertEquals("Living Room PC", s!!.name)
    }

    @Test
    fun `udp port prefers the TXT record over the SRV port`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9876,
                txt = mapOf("udp" to bytes("9900")),
            )
        assertEquals(9900, s!!.udpPort)
    }

    @Test
    fun `udp port falls back to the SRV port when no TXT udp`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9881,
                txt = emptyMap(),
            )
        assertEquals(9881, s!!.udpPort)
    }

    @Test
    fun `udp port falls back to the protocol default when SRV port is zero`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 0,
                txt = emptyMap(),
            )
        assertEquals(MDNS_DEFAULT_UDP, s!!.udpPort)
        assertEquals(9876, s.udpPort)
    }

    @Test
    fun `udp port falls back to the protocol default when SRV port is negative`() {
        val s =
            mdnsServiceToServer("Sat", "10.0.0.1", srvPort = -1, txt = emptyMap())
        assertEquals(MDNS_DEFAULT_UDP, s!!.udpPort)
    }

    @Test
    fun `pair and http ports come from their TXT records`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9876,
                txt =
                    mapOf(
                        "pair" to bytes("19878"),
                        "http" to bytes("18080"),
                    ),
            )
        assertEquals(19878, s!!.pairPort)
        assertEquals(18080, s.httpPort)
    }

    @Test
    fun `pair and http fall back to protocol defaults when absent`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9876,
                txt = emptyMap(),
            )
        assertEquals(MDNS_DEFAULT_PAIR, s!!.pairPort)
        assertEquals(MDNS_DEFAULT_HTTP, s.httpPort)
        assertEquals(9443, s.pairPort)
        assertEquals(9443, s.httpPort)
    }

    @Test
    fun `all three ports resolve independently from a full TXT set`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Full",
                hostAddress = "192.168.1.50",
                srvPort = 1234,
                txt =
                    mapOf(
                        "udp" to bytes("9001"),
                        "pair" to bytes("9002"),
                        "http" to bytes("9003"),
                    ),
            )
        assertEquals("Full", s!!.name)
        assertEquals("192.168.1.50", s.ip)
        assertEquals(9001, s.udpPort)
        assertEquals(9002, s.pairPort)
        assertEquals(9003, s.httpPort)
    }

    @Test
    fun `a garbage TXT port falls through to the SRV port or default`() {
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9882,
                txt = mapOf("udp" to bytes("xxxx")),
            )
        assertEquals(9882, s!!.udpPort)
    }

    @Test
    fun `machineId comes from the mid TXT record`() {
        val s = mdnsServiceToServer("Sat", "10.0.0.1", 9876, mapOf("mid" to bytes("deadbeef")))
        assertEquals("deadbeef", s!!.machineId)
    }

    @Test
    fun `machineId is empty when no mid TXT record is present`() {
        val s = mdnsServiceToServer("Sat", "10.0.0.1", 9876, emptyMap())
        assertEquals("", s!!.machineId)
    }

    @Test
    fun `txtString trims, and rejects empty or missing values`() {
        assertEquals("x", mdnsTxtString(mapOf("mid" to bytes(" x ")), "mid"))
        assertNull(mdnsTxtString(mapOf("mid" to bytes("   ")), "mid"))
        assertNull(mdnsTxtString(emptyMap(), "mid"))
    }
}
