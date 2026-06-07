// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.sensor.BatteryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    private fun summary(
        id: String,
        live: LinkState,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    @Test
    fun `default state has zero streaming slots`() {
        assertEquals(0, MainUiState().streamingSlotCount)
    }

    @Test
    fun `connections without any bound slot do not count as streaming`() {
        val s1 = summary("s:1", LinkState.Connected)
        val s2 = summary("s:2", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = VIRTUAL_SLOT_ID,
                            inputType = SlotInputType.VIRTUAL,
                            name = "Virtual",
                            boundConnectionId = "s:1",
                            boundStatus = s1,
                        ),
                    ),
                connections = listOf(s1, s2),
            )

        assertEquals(1, state.streamingSlotCount)
    }

    @Test
    fun `bound slot with IDLE connection is not streaming`() {
        val idle = summary("s:1", LinkState.Saved)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = VIRTUAL_SLOT_ID,
                            inputType = SlotInputType.VIRTUAL,
                            name = "V",
                            boundConnectionId = "s:1",
                            boundStatus = idle,
                        ),
                    ),
                connections = listOf(idle),
            )

        assertEquals(0, state.streamingSlotCount)
    }

    @Test
    fun `bound slot with CONNECTING connection is not streaming yet`() {
        val connecting = summary("s:1", LinkState.Connecting)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = VIRTUAL_SLOT_ID,
                            inputType = SlotInputType.VIRTUAL,
                            name = "V",
                            boundConnectionId = "s:1",
                            boundStatus = connecting,
                        ),
                    ),
                connections = listOf(connecting),
            )

        assertEquals(0, state.streamingSlotCount)
    }

    @Test
    fun `slot in disconnect grace period does not count`() {
        val live = summary("s:1", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = "phys-1",
                            inputType = SlotInputType.PHYSICAL,
                            name = "P1",
                            physicalDeviceId = 7,
                            boundConnectionId = "s:1",
                            boundStatus = live,
                            isDisconnecting = true,
                            disconnectTimeLeft = 3,
                        ),
                    ),
                connections = listOf(live),
            )

        assertEquals(0, state.streamingSlotCount)
    }

    @Test
    fun `physical slot bound and connected without a device does not count`() {
        // physicalDeviceId default -1 = no real input device.
        val live = summary("s:1", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = "phys-1",
                            inputType = SlotInputType.PHYSICAL,
                            name = "P1",
                            physicalDeviceId = -1,
                            boundConnectionId = "s:1",
                            boundStatus = live,
                        ),
                    ),
                connections = listOf(live),
            )

        assertEquals(0, state.streamingSlotCount)
    }

    @Test
    fun `physical slot with device bound to live connection counts as one`() {
        val live = summary("s:1", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = "phys-1",
                            inputType = SlotInputType.PHYSICAL,
                            name = "P1",
                            physicalDeviceId = 11,
                            boundConnectionId = "s:1",
                            boundStatus = live,
                        ),
                    ),
                connections = listOf(live),
            )

        assertEquals(1, state.streamingSlotCount)
    }

    @Test
    fun `multiple physical slots with devices each count once`() {
        val live = summary("s:1", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = "phys-1",
                            inputType = SlotInputType.PHYSICAL,
                            name = "P1",
                            physicalDeviceId = 11,
                            boundConnectionId = "s:1",
                            boundStatus = live,
                        ),
                        ControllerSlot(
                            id = "phys-2",
                            inputType = SlotInputType.PHYSICAL,
                            name = "P2",
                            physicalDeviceId = 12,
                            boundConnectionId = "s:1",
                            boundStatus = live,
                        ),
                    ),
                connections = listOf(live),
            )

        assertEquals(2, state.streamingSlotCount)
    }

    @Test
    fun `unbound slot never counts even when connections are live`() {
        val live = summary("s:1", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        ControllerSlot(
                            id = VIRTUAL_SLOT_ID,
                            inputType = SlotInputType.VIRTUAL,
                            name = "V",
                            boundConnectionId = null,
                        ),
                    ),
                connections = listOf(live),
            )

        assertEquals(0, state.streamingSlotCount)
    }

    @Test
    fun `fromWire keeps a known level and discharging status`() {
        val ui = BatteryUi.fromWire(64, BatteryValidator.STATUS_DISCHARGING)
        assertEquals(64, ui?.level)
        assertFalse(ui!!.charging)
    }

    @Test
    fun `fromWire marks charging full and wired states as charging`() {
        assertTrue(BatteryUi.fromWire(50, BatteryValidator.STATUS_CHARGING)!!.charging)
        assertTrue(BatteryUi.fromWire(100, BatteryValidator.STATUS_FULL)!!.charging)
        assertTrue(BatteryUi.fromWire(100, BatteryValidator.STATUS_WIRED)!!.charging)
    }

    @Test
    fun `fromWire collapses the unknown-level unknown-status pair to null`() {
        assertNull(
            BatteryUi.fromWire(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_UNKNOWN),
        )
    }

    @Test
    fun `fromWire keeps an unknown level when the status is known`() {
        val ui = BatteryUi.fromWire(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_CHARGING)
        assertNull(ui?.level)
        assertTrue(ui!!.charging)
    }

    @Test
    fun `isLow is true only for a low non-charging battery`() {
        assertTrue(BatteryUi.fromWire(10, BatteryValidator.STATUS_DISCHARGING)!!.isLow)
        assertTrue(
            "the threshold itself counts as low",
            BatteryUi.fromWire(BatteryUi.LOW_THRESHOLD, BatteryValidator.STATUS_DISCHARGING)!!.isLow,
        )
        assertFalse(BatteryUi.fromWire(50, BatteryValidator.STATUS_DISCHARGING)!!.isLow)
        assertFalse(BatteryUi.fromWire(5, BatteryValidator.STATUS_CHARGING)!!.isLow)
        assertFalse(
            BatteryUi.fromWire(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_DISCHARGING)!!.isLow,
        )
    }

    @Test
    fun `anyConnected reflects connections list and is independent of streaming count`() {
        val live = summary("s:1", LinkState.Connected)
        val state = MainUiState(connections = listOf(live))

        assert(state.anyConnected)
        assertEquals(0, state.streamingSlotCount)
    }

    @Test
    fun `anyConnecting is true only while a connection is connecting`() {
        assertTrue(MainUiState(connections = listOf(summary("s:1", LinkState.Connecting))).anyConnecting)
        assertFalse(MainUiState(connections = listOf(summary("s:1", LinkState.Connected))).anyConnecting)
        assertFalse(MainUiState().anyConnecting)
    }
}
