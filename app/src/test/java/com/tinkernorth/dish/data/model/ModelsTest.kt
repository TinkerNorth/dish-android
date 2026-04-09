package com.tinkernorth.dish.data.model

import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.main.DashboardCardRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for model types: [ControllerType], [ControllerCardState],
 * [ControllerEntry], [DiscoveredServer].
 */
class ModelsTest {

    // ── ControllerType ────────────────────────────────────────────────────

    @Test
    fun `ControllerType labels matches entries`() {
        assertEquals(ControllerType.entries.size, ControllerType.labels.size)
        assertEquals("Xbox", ControllerType.labels[0])
        assertEquals("PlayStation", ControllerType.labels[1])
    }

    @Test
    fun `ControllerType fromIndex returns correct type`() {
        assertEquals(ControllerType.XBOX, ControllerType.fromIndex(0))
        assertEquals(ControllerType.PLAYSTATION, ControllerType.fromIndex(1))
    }

    @Test
    fun `ControllerType fromIndex out of range defaults to XBOX`() {
        assertEquals(ControllerType.XBOX, ControllerType.fromIndex(-1))
        assertEquals(ControllerType.XBOX, ControllerType.fromIndex(99))
    }

    @Test
    fun `ControllerType wire values are distinct`() {
        val wireValues = ControllerType.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ── ControllerEntry defaults ──────────────────────────────────────────

    @Test
    fun `ControllerEntry has correct defaults`() {
        val entry = ControllerEntry(androidDeviceId = 1, name = "Test")
        assertEquals(-1, entry.controllerIndex)
        assertEquals(ControllerCardState.NEED_SERVER, entry.cardState)
        assertEquals(false, entry.vigemActive)
        assertEquals(ControllerType.XBOX, entry.controllerType)
        assertEquals(null, entry.countdownTimer)
        assertEquals(10, entry.countdownSeconds)
    }

    // ── ControllerCardState ───────────────────────────────────────────────

    @Test
    fun `all ControllerCardState values are distinct`() {
        val values = ControllerCardState.entries
        assertEquals(values.size, values.toSet().size)
        assertEquals(6, values.size)
    }

    // ── DiscoveredServer ──────────────────────────────────────────────────

    @Test
    fun `DiscoveredServer data class equality`() {
        val a = DiscoveredServer("PC", "10.0.0.1", 9876, 9878, 9877)
        val b = DiscoveredServer("PC", "10.0.0.1", 9876, 9878, 9877)
        assertEquals(a, b)
    }

    // ── DashboardCardRenderer.stateColor ──────────────────────────────────

    @Test
    fun `stateColor returns success for ACTIVE`() {
        assertEquals(R.color.colorSuccess, DashboardCardRenderer.stateColor(ControllerCardState.ACTIVE))
    }

    @Test
    fun `stateColor returns warning for DISCONNECTING`() {
        assertEquals(R.color.colorWarning, DashboardCardRenderer.stateColor(ControllerCardState.DISCONNECTING))
    }

    @Test
    fun `stateColor returns primary for SCANNING`() {
        assertEquals(R.color.colorPrimary, DashboardCardRenderer.stateColor(ControllerCardState.SCANNING))
    }

    @Test
    fun `stateColor returns primary for ADDING`() {
        assertEquals(R.color.colorPrimary, DashboardCardRenderer.stateColor(ControllerCardState.ADDING))
    }

    @Test
    fun `stateColor returns muted for NEED_SERVER`() {
        assertEquals(R.color.colorMuted, DashboardCardRenderer.stateColor(ControllerCardState.NEED_SERVER))
    }

    @Test
    fun `stateColor returns muted for SERVER_LIST`() {
        assertEquals(R.color.colorMuted, DashboardCardRenderer.stateColor(ControllerCardState.SERVER_LIST))
    }
}
