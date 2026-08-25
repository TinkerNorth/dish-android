// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightRowsTest {
    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.MOONLIGHT,
    ) = ConnectionSummary(id = id, kind = kind, label = id, detail = "", live = LinkState.Saved, boundSlotIds = emptyList())

    @Test
    fun `known moonlight hosts come first, then discovered hosts not already known`() {
        val known = summary("moonlight:uid:a")
        val bt = summary("bt:x", ConnectionKind.BLUETOOTH)
        val discoveredKnown = MoonlightHost(name = "A", address = "10.0.0.1", uniqueId = "a")
        val discoveredNew = MoonlightHost(name = "B", address = "10.0.0.2", uniqueId = "b")

        val rows = moonlightRows(listOf(known, bt), listOf(discoveredKnown, discoveredNew))

        assertEquals(2, rows.size)
        assertTrue(rows[0] is MoonlightRow.Known)
        assertEquals("moonlight:uid:a", (rows[0] as MoonlightRow.Known).summary.id)
        assertTrue(rows[1] is MoonlightRow.Discovered)
        assertEquals("moonlight:uid:b", (rows[1] as MoonlightRow.Discovered).host.id)
    }

    @Test
    fun `bluetooth and satellite summaries are excluded`() {
        val rows = moonlightRows(listOf(summary("sat:1", ConnectionKind.SATELLITE)), emptyList())
        assertTrue(rows.isEmpty())
    }
}
