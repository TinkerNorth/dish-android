package com.tinkernorth.dish.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for model types: [ControllerEntry], [DiscoveredServer].
 */
class ModelsTest {
    // ── ControllerEntry defaults ──────────────────────────────────────────

    @Test
    fun `ControllerEntry has correct defaults`() {
        val entry = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        assertEquals(0, entry.controllerIndex)
        assertFalse(entry.isDisconnected)
        assertEquals(0, entry.disconnectTimeLeft)
    }

    @Test
    fun `ControllerEntry equality`() {
        val a = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        val b = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        assertEquals(a, b)
    }

    @Test
    fun `ControllerEntry copy with disconnected state`() {
        val entry = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        val disconnected = entry.copy(isDisconnected = true, disconnectTimeLeft = 30)
        assertEquals(true, disconnected.isDisconnected)
        assertEquals(30, disconnected.disconnectTimeLeft)
    }

    // ── DiscoveredServer ──────────────────────────────────────────────────

    @Test
    fun `DiscoveredServer data class equality`() {
        val a = DiscoveredServer("PC", "10.0.0.1", 9876, 9878, 9877)
        val b = DiscoveredServer("PC", "10.0.0.1", 9876, 9878, 9877)
        assertEquals(a, b)
    }

    @Test
    fun `PairResponse defaults`() {
        val response = PairResponse()
        assertFalse(response.ok)
        assertEquals(null, response.error)
        assertEquals(null, response.sharedKey)
    }

    @Test
    fun `ConnectResponse defaults`() {
        val response = ConnectResponse()
        assertEquals(null, response.connectionId)
        assertEquals(null, response.token)
        assertEquals(null, response.error)
    }
}
