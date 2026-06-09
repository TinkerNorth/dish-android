// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.core.model.DiscoveredServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun `jsonGet extracts string value`() {
        val json = """{"name":"MyServer","ip":"192.168.1.1"}"""
        assertEquals("MyServer", jsonGet(json, "name"))
        assertEquals("192.168.1.1", jsonGet(json, "ip"))
    }

    @Test
    fun `jsonGet extracts numeric value`() {
        val json = """{"udpPort":9876,"pairPort":9878}"""
        assertEquals("9876", jsonGet(json, "udpPort"))
    }

    @Test
    fun `jsonGet extracts boolean value`() {
        val json = """{"ok":true}"""
        assertEquals("true", jsonGet(json, "ok"))
    }

    @Test
    fun `jsonGet returns null for missing key`() {
        val json = """{"name":"Test"}"""
        assertNull(jsonGet(json, "missing"))
    }

    @Test
    fun `jsonGet handles spaces after colon`() {
        val json = """{"key": "value"}"""
        assertEquals("value", jsonGet(json, "key"))
    }

    @Test
    fun `jsonGet handles empty string value`() {
        val json = """{"key":""}"""
        assertEquals("", jsonGet(json, "key"))
    }

    @Test
    fun `jsonGet with error field`() {
        val json = """{"ok":false,"error":"PIN required"}"""
        assertEquals("false", jsonGet(json, "ok"))
        assertEquals("PIN required", jsonGet(json, "error"))
    }

    @Test
    fun `hexToBytes converts even-length hex string`() {
        val bytes = hexToBytes("DEADBEEF")
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), bytes)
    }

    @Test
    fun `hexToBytes empty string returns empty array`() {
        assertArrayEquals(byteArrayOf(), hexToBytes(""))
    }

    @Test
    fun `hexToBytes lowercase hex`() {
        val bytes = hexToBytes("0a0b0c")
        assertEquals(3, bytes.size)
        assertEquals(0x0A.toByte(), bytes[0])
        assertEquals(0x0B.toByte(), bytes[1])
        assertEquals(0x0C.toByte(), bytes[2])
    }

    @Test
    fun `hexToBytes produces correct length for 64-char key`() {
        val hex = "0123456789abcdef".repeat(4)
        val bytes = hexToBytes(hex)
        assertEquals(32, bytes.size)
    }

    @Test
    fun `hexToBytes rejects a non-hex character`() {
        try {
            hexToBytes("0g")
            fail("expected IllegalArgumentException for non-hex input")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `hexToBytes rejects odd-length input`() {
        try {
            hexToBytes("abc")
            fail("expected IllegalArgumentException for odd-length input")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `hexToBytes decodes valid mixed-case hex correctly`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1F.toByte(), 0xA0.toByte(), 0xFf.toByte()),
            hexToBytes("001fA0fF"),
        )
    }

    @Test
    fun `isPrivateHostLiteral accepts private and local literals`() {
        val privateHosts =
            listOf(
                "10.0.0.5",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.1.1",
                "169.254.1.1",
                "127.0.0.1",
                "::1",
                "fe80::1",
                "[fe80::1]",
                "fc00::1",
            )
        for (h in privateHosts) {
            assertTrue("expected $h to be private", isPrivateHostLiteral(h))
        }
    }

    @Test
    fun `isPrivateHostLiteral rejects public, out-of-range, and non-literals`() {
        val nonPrivateHosts =
            listOf(
                "8.8.8.8",
                "172.32.0.1", // just past the 172.16/12 upper bound
                "11.0.0.1", // only 10.0.0.0/8 is private, not 11.x
                "172.15.0.1", // just below the 172.16/12 lower bound
                "example.com",
                "",
                "999.1.1.1", // octet out of range
                "1.2.3", // too few octets
            )
        for (h in nonPrivateHosts) {
            assertFalse("expected $h to be non-private", isPrivateHostLiteral(h))
        }
    }

    @Test
    fun `parseServers empty array`() {
        assertEquals(emptyList<DiscoveredServer>(), parseServers("[]"))
    }

    @Test
    fun `parseServers empty string`() {
        assertEquals(emptyList<DiscoveredServer>(), parseServers(""))
    }

    @Test
    fun `parseServers single server`() {
        val json = """[{"name":"PC","ip":"10.0.0.1","udpPort":9876,"pairPort":9878,"httpPort":9877}]"""
        val servers = parseServers(json)
        assertEquals(1, servers.size)
        assertEquals("PC", servers[0].name)
        assertEquals("10.0.0.1", servers[0].ip)
        assertEquals(9876, servers[0].udpPort)
        assertEquals(9878, servers[0].pairPort)
        assertEquals(9877, servers[0].httpPort)
    }

    @Test
    fun `parseServers multiple servers`() {
        val json = """[{"name":"A","ip":"1.1.1.1"},{"name":"B","ip":"2.2.2.2"}]"""
        val servers = parseServers(json)
        assertEquals(2, servers.size)
        assertEquals("A", servers[0].name)
        assertEquals("B", servers[1].name)
    }

    @Test
    fun `parseServers uses default ports when missing`() {
        val json = """[{"name":"X","ip":"3.3.3.3"}]"""
        val servers = parseServers(json)
        assertEquals(9876, servers[0].udpPort)
        assertEquals(9443, servers[0].pairPort)
        assertEquals(9443, servers[0].httpPort)
    }

    @Test
    fun `parseServers skips entries without name`() {
        val json = """[{"ip":"1.1.1.1"},{"name":"OK","ip":"2.2.2.2"}]"""
        val servers = parseServers(json)
        assertEquals(1, servers.size)
        assertEquals("OK", servers[0].name)
    }

    @Test
    fun `parseServers skips entries without ip`() {
        val json = """[{"name":"NoIP"},{"name":"OK","ip":"2.2.2.2"}]"""
        val servers = parseServers(json)
        assertEquals(1, servers.size)
        assertEquals("OK", servers[0].name)
    }
}
