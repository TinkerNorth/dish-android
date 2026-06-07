// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StableKeyTest {
    @Test
    fun `prefers machineId when present`() {
        val s = DiscoveredServer(ip = "192.168.1.5", udpPort = 9876, machineId = "abc123")
        assertEquals("mid:abc123", s.stableKey)
    }

    @Test
    fun `falls back to ip and port without a machineId`() {
        val s = DiscoveredServer(ip = "192.168.1.5", udpPort = 9876)
        assertEquals("192.168.1.5:9876", s.stableKey)
    }

    @Test
    fun `same machineId at a different IP yields the same key - the dedupe fix`() {
        val a = DiscoveredServer(ip = "192.168.1.5", udpPort = 9876, machineId = "m")
        val b = DiscoveredServer(ip = "192.168.1.99", udpPort = 9876, machineId = "m")
        assertEquals(a.stableKey, b.stableKey)
    }

    @Test
    fun `a blank machineId is treated as absent`() {
        val s = DiscoveredServer(ip = "10.0.0.1", udpPort = 9876, machineId = "   ")
        assertEquals("10.0.0.1:9876", s.stableKey)
    }
}
