// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.model.DiscoverySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure mDNS → [com.tinkernorth.dish.data.model.DiscoveredServer]
 * mapping ([mdnsServiceToServer] / [mdnsTxtInt]) lifted out of
 * [MdnsDiscovery.toServer].
 *
 * Covers the roadmap-deliverable TXT-record parsing: the SRV-port-vs-TXT-port
 * fallback, the protocol default ports, and the missing-host → null case.
 */
class MdnsDiscoveryMappingTest {
    /** UTF-8 bytes, the form NsdManager hands TXT values back as. */
    private fun bytes(s: String): ByteArray = s.toByteArray()

    // ── mdnsTxtInt ──────────────────────────────────────────────────────────

    @Test
    fun `txtInt parses a numeric value`() {
        assertEquals(9876, mdnsTxtInt(mapOf("udp" to bytes("9876")), "udp"))
    }

    @Test
    fun `txtInt trims surrounding whitespace`() {
        // Some responders pad TXT values; the parser must tolerate it.
        assertEquals(9878, mdnsTxtInt(mapOf("pair" to bytes(" 9878 ")), "pair"))
    }

    @Test
    fun `txtInt returns null for a missing key`() {
        assertNull(mdnsTxtInt(mapOf("udp" to bytes("9876")), "http"))
    }

    @Test
    fun `txtInt returns null for a null value`() {
        // NsdServiceInfo.getAttributes() can map a key to a null value.
        assertNull(mdnsTxtInt(mapOf<String, ByteArray?>("udp" to null), "udp"))
    }

    @Test
    fun `txtInt returns null for a non-numeric value`() {
        assertNull(mdnsTxtInt(mapOf("udp" to bytes("not-a-port")), "udp"))
    }

    // ── mdnsServiceToServer: host ───────────────────────────────────────────

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

    // ── mdnsServiceToServer: name fallback ──────────────────────────────────

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

    // ── mdnsServiceToServer: udp port — SRV vs TXT vs default ───────────────

    @Test
    fun `udp port prefers the TXT record over the SRV port`() {
        // TXT udp=9900 must win even though the SRV port is a valid 9876.
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
        // srvPort == 0 means "not advertised" — must not become a 0 udpPort.
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

    // ── mdnsServiceToServer: pair / http ports ──────────────────────────────

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
        // The SRV port advertises the UDP service only — it is NOT a fallback
        // for pair / http; those default independently.
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9876,
                txt = emptyMap(),
            )
        assertEquals(MDNS_DEFAULT_PAIR, s!!.pairPort)
        assertEquals(MDNS_DEFAULT_HTTP, s.httpPort)
        // Pairing + connection API share the satellite's single HTTPS client
        // server on 9443; the legacy split 9877/9878 ports are gone.
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
        // A non-numeric TXT udp value must not crash and must not win — it
        // falls through exactly as a missing key would.
        val s =
            mdnsServiceToServer(
                serviceName = "Sat",
                hostAddress = "10.0.0.1",
                srvPort = 9882,
                txt = mapOf("udp" to bytes("xxxx")),
            )
        assertEquals(9882, s!!.udpPort)
    }
}
