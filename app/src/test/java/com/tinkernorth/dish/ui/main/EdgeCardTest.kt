// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The banner over a bound card: what it says and which buttons it offers, per edge.
class EdgeCardTest {
    private fun summary(
        id: String = "host",
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(
        id = id,
        kind = ConnectionKind.SATELLITE,
        label = "Desk PC",
        detail = "",
        live = live,
        boundSlotIds = listOf("9"),
    )

    private fun row(
        connections: List<ConnectionSummary> = listOf(summary()),
        disconnectTimeLeft: Int = 0,
    ) = ControllerRow(
        slot =
            ControllerSlot(
                id = "9",
                inputType = SlotInputType.PHYSICAL,
                name = "Pad",
                boundConnectionId = "host",
                boundStatus = summary(),
                disconnectTimeLeft = disconnectTimeLeft,
            ),
        connections = connections,
    )

    @Test
    fun `the quiet card has no banner`() {
        assertNull(edgeCardFor(EdgeState.NONE, row()))
    }

    @Test
    fun `a host lost edge names the host and offers reconnect and configure`() {
        val card = edgeCardFor(EdgeState.HOST_LOST, row())
        assertEquals(EdgePrimary.RECONNECT, card?.primary)
        assertEquals(EdgeSecondary.CONFIGURE, card?.secondary)
        assertEquals(R.string.binding_edge_host_lost_detail, card?.detailRes)
        assertEquals("Desk PC", card?.detailArg)
        assertEquals(R.color.colorError, card?.accentRes)
        assertNull(card?.countdownSec)
    }

    @Test
    fun `a host lost edge shows a spinner while any connection is connecting`() {
        val reconnecting = listOf(summary(), summary(id = "other", live = LinkState.Connecting))
        assertEquals(EdgePrimary.RECONNECT_PENDING, edgeCardFor(EdgeState.HOST_LOST, row(reconnecting))?.primary)
    }

    @Test
    fun `an input lost edge counts down and offers only unbind`() {
        val card = edgeCardFor(EdgeState.INPUT_LOST, row(disconnectTimeLeft = 4))
        assertEquals(EdgePrimary.UNBIND, card?.primary)
        assertEquals(EdgeSecondary.NONE, card?.secondary)
        assertEquals(4, card?.countdownSec)
        assertNull(card?.detailArg)
        assertEquals(R.color.colorWarning, card?.accentRes)
    }

    @Test
    fun `an unsteady edge offers only its dismissal`() {
        val card = edgeCardFor(EdgeState.UNSTEADY, row())
        assertEquals(EdgePrimary.NONE, card?.primary)
        assertEquals(EdgeSecondary.DISMISS, card?.secondary)
        assertEquals(R.string.binding_edge_unsteady_title, card?.titleRes)
    }

    @Test
    fun `a disconnecting card with no banner fades to half`() {
        assertEquals(HALF_FADED, cardAlpha(EdgeState.NONE, isDisconnecting = true), EXACT)
    }

    @Test
    fun `a card that is not disconnecting stays opaque`() {
        assertEquals(OPAQUE, cardAlpha(EdgeState.NONE, isDisconnecting = false), EXACT)
    }

    // The banner carries the news itself, so the card under it is not faded as well.
    @Test
    fun `a disconnecting card under a banner stays opaque`() {
        assertEquals(OPAQUE, cardAlpha(EdgeState.INPUT_LOST, isDisconnecting = true), EXACT)
    }

    private companion object {
        const val HALF_FADED = 0.5f
        const val OPAQUE = 1f
        const val EXACT = 0f
    }
}
