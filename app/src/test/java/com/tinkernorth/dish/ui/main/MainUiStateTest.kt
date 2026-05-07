// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.ConnectionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [MainUiState] derived properties.
 *
 * The interesting one is [MainUiState.streamingSlotCount]: it powers the
 * "Streaming · N controllers" line on the low-power overlay, and its prior
 * implementation (count of CONNECTED *connections*) over-reported when a
 * remembered connection was live but no slot was actually routing input to
 * it.
 */
class MainUiStateTest {
    private fun summary(
        id: String,
        live: ConnectionLive,
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
        // The user's exact scenario: two CONNECTED connections, one active
        // controller. The slot for the second connection has nothing plugged
        // into it. Old logic returned 2; new logic returns 1.
        val s1 = summary("s:1", ConnectionLive.CONNECTED)
        val s2 = summary("s:2", ConnectionLive.CONNECTED)
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
        val idle = summary("s:1", ConnectionLive.IDLE)
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
        val connecting = summary("s:1", ConnectionLive.CONNECTING)
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
        // A physical controller that just got unplugged is held for a short
        // grace period so quick replug-cycles don't churn the UI. While in
        // that grace it must not count as streaming.
        val live = summary("s:1", ConnectionLive.CONNECTED)
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
        // physicalDeviceId defaults to -1, meaning no real input device. We
        // can't be "streaming" if there is no controller plugged in.
        val live = summary("s:1", ConnectionLive.CONNECTED)
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
        val live = summary("s:1", ConnectionLive.CONNECTED)
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
        val live = summary("s:1", ConnectionLive.CONNECTED)
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
        val live = summary("s:1", ConnectionLive.CONNECTED)
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
    fun `anyConnected reflects connections list and is independent of streaming count`() {
        val live = summary("s:1", ConnectionLive.CONNECTED)
        val state = MainUiState(connections = listOf(live))

        // anyConnected stays connection-derived (used to decide wake-lock
        // policy); streaming slots are derived from bound slots and stay zero
        // until something is actually plugged in and bound.
        assert(state.anyConnected)
        assertEquals(0, state.streamingSlotCount)
    }
}
