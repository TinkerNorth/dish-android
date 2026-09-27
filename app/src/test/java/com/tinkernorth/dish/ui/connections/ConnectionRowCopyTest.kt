// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRowCopyTest {
    @Test
    fun `a live link offers to disconnect, unstable included`() {
        assertEquals(RowAction.DISCONNECT, primaryActionFor(LinkState.Connected))
        assertEquals(RowAction.DISCONNECT, primaryActionFor(LinkState.Unstable))
    }

    @Test
    fun `a connecting link only shows its spinner`() {
        assertEquals(RowAction.CONNECTING, primaryActionFor(LinkState.Connecting))
    }

    @Test
    fun `a stale link offers to re-pair`() {
        assertEquals(RowAction.REPAIR, primaryActionFor(LinkState.Stale))
    }

    @Test
    fun `a saved, ready or found link offers to connect`() {
        assertEquals(RowAction.CONNECT, primaryActionFor(LinkState.Saved))
        assertEquals(RowAction.CONNECT, primaryActionFor(LinkState.Ready))
        assertEquals(RowAction.CONNECT, primaryActionFor(LinkState.Found))
    }

    @Test
    fun `every link state has exactly one primary action`() {
        val actions = LinkState.entries.map { primaryActionFor(it) }
        assertEquals(LinkState.entries.size, actions.size)
        assertEquals(RowAction.entries.toSet(), actions.toSet())
    }

    @Test
    fun `a Moonlight host in use offers Quit instead of Pair and shows its count`() {
        val copy = moonlightRowCopy(known(MoonlightTrustState.PAIRED, controllerCount = 2))
        assertEquals(R.string.ml_action_quit_session, copy.primaryLabelRes)
        assertTrue(copy.inUse)
    }

    @Test
    fun `an idle paired host offers to re-pair without an in-use count`() {
        val copy = moonlightRowCopy(known(MoonlightTrustState.PAIRED, controllerCount = 0))
        assertEquals(R.string.action_repair_short, copy.primaryLabelRes)
        assertFalse(copy.inUse)
    }

    @Test
    fun `an idle unpaired host offers to pair`() {
        val copy = moonlightRowCopy(known(MoonlightTrustState.NOT_PAIRED, controllerCount = 0))
        assertEquals(R.string.ml_action_pair, copy.primaryLabelRes)
        assertFalse(copy.inUse)
    }

    private fun known(
        trust: MoonlightTrustState,
        controllerCount: Int,
    ): MoonlightRow.Known {
        val summary =
            ConnectionSummary(
                id = "moonlight:uid:a",
                kind = ConnectionKind.MOONLIGHT,
                label = "PC",
                detail = "",
                live = LinkState.Saved,
                boundSlotIds = List(controllerCount) { it.toString() },
            )
        return MoonlightRow.Known(summary = summary, trust = trust, controllerCount = controllerCount)
    }
}
