// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightRowsTest {
    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.MOONLIGHT,
        live: LinkState = LinkState.Saved,
        boundSlotIds: List<String> = emptyList(),
    ) = ConnectionSummary(id = id, kind = kind, label = id, detail = "", live = live, boundSlotIds = boundSlotIds)

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

    // The three trust words, and never a liveness light: a live session or a call the host
    // authorised this visit proves the pairing stands, a stored record only remembers it,
    // anything else is not paired.
    @Test
    fun `a live session proves the pairing, a stored record only remembers it`() {
        val live = summary("moonlight:uid:a", live = LinkState.Connected)
        assertEquals(MoonlightTrustState.PAIRED, moonlightTrustFor(live, paired = false))
        assertEquals(
            MoonlightTrustState.PAIRED,
            moonlightTrustFor(summary("moonlight:uid:a", live = LinkState.Unstable), paired = true),
        )
        assertEquals(MoonlightTrustState.REMEMBERED, moonlightTrustFor(summary("moonlight:uid:a"), paired = true))
        assertEquals(MoonlightTrustState.NOT_PAIRED, moonlightTrustFor(summary("moonlight:uid:a"), paired = false))
    }

    // The hosts screen never probes, so without this a pairing the user just watched
    // succeed still read as merely remembered.
    @Test
    fun `a host verified this visit reads as paired without a session`() {
        val idle = summary("moonlight:uid:a")
        assertEquals(MoonlightTrustState.PAIRED, moonlightTrustFor(idle, paired = true, verified = true))
        assertEquals(MoonlightTrustState.PAIRED, moonlightTrustFor(idle, paired = false, verified = true))
    }

    @Test
    fun `a known row carries its trust word and the controllers bound to it`() {
        val rows =
            moonlightRows(
                conns = listOf(summary("moonlight:uid:a", live = LinkState.Connected, boundSlotIds = listOf("1", "2"))),
                discovered = emptyList(),
                pairedIds = setOf("moonlight:uid:a"),
            )
        val known = rows.single() as MoonlightRow.Known
        assertEquals(MoonlightTrustState.PAIRED, known.trust)
        assertEquals(2, known.controllerCount)
    }

    // A record written for a binding has never been accepted by the host it names.
    @Test
    fun `a host remembered as interest only is not paired`() {
        val rows =
            moonlightRows(
                conns = listOf(summary("moonlight:10.0.0.9")),
                discovered = emptyList(),
                pairedIds = emptySet(),
            )
        assertEquals(MoonlightTrustState.NOT_PAIRED, (rows.single() as MoonlightRow.Known).trust)
    }

    @Test
    fun `a discovered host is never claimed as remembered`() {
        val rows = moonlightRows(emptyList(), listOf(MoonlightHost(name = "B", address = "10.0.0.2", uniqueId = "b")))
        assertTrue(rows.single() is MoonlightRow.Discovered)
    }
}
