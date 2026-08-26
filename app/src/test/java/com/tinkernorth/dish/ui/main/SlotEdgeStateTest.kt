// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

// The dashboard's edge banner. A satellite that stops answering is a real loss; a
// Moonlight host has no live link to lose, so it never raises one.
class SlotEdgeStateTest {
    private fun summary(
        kind: ConnectionKind,
        live: LinkState,
    ) = ConnectionSummary(
        id = "host",
        kind = kind,
        label = "PC",
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun slot(
        kind: ConnectionKind = ConnectionKind.SATELLITE,
        live: LinkState = LinkState.Connected,
        bound: Boolean = true,
        disconnecting: Boolean = false,
    ) = ControllerSlot(
        id = "1",
        inputType = SlotInputType.VIRTUAL,
        name = "Pad",
        boundConnectionId = if (bound) "host" else null,
        boundStatus = if (bound) summary(kind, live) else null,
        isDisconnecting = disconnecting,
    )

    @Test
    fun `an unbound slot has no edge`() {
        assertEquals(EdgeState.NONE, slotEdgeState(slot(bound = false)))
    }

    @Test
    fun `a departing input outranks everything`() {
        assertEquals(EdgeState.INPUT_LOST, slotEdgeState(slot(disconnecting = true)))
        assertEquals(
            EdgeState.INPUT_LOST,
            slotEdgeState(slot(kind = ConnectionKind.MOONLIGHT, disconnecting = true)),
        )
    }

    @Test
    fun `a satellite that stopped answering is still reported lost`() {
        assertEquals(EdgeState.HOST_LOST, slotEdgeState(slot(live = LinkState.Saved)))
        assertEquals(EdgeState.HOST_LOST, slotEdgeState(slot(live = LinkState.Connecting)))
        assertEquals(EdgeState.UNSTEADY, slotEdgeState(slot(live = LinkState.Unstable)))
        assertEquals(EdgeState.NONE, slotEdgeState(slot(live = LinkState.Connected)))
    }

    @Test
    fun `a Moonlight host is never lost, in any link state`() {
        LinkState.entries.forEach { live ->
            assertEquals(
                live.name,
                EdgeState.NONE,
                slotEdgeState(slot(kind = ConnectionKind.MOONLIGHT, live = live)),
            )
        }
    }
}
