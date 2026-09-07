// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkHistoryPolicyTest {
    private fun summary(
        id: String,
        live: LinkState,
    ) = ConnectionSummary(id = id, kind = ConnectionKind.SATELLITE, label = id, detail = "", live = live, boundSlotIds = emptyList())

    @Test
    fun `a link coming up counts one connect and remembers when`() {
        val s1 = LinkHistoryPolicy.onConnections(LinkHistoryState(), listOf(summary("a", LinkState.Saved)), nowMs = 10)
        assertEquals(0, s1.connections.getValue("a").connects)
        assertNull(s1.connections.getValue("a").upSinceMs)

        val s2 = LinkHistoryPolicy.onConnections(s1, listOf(summary("a", LinkState.Connected)), nowMs = 20)
        assertEquals(1, s2.connections.getValue("a").connects)
        assertEquals(20L, s2.connections.getValue("a").upSinceMs)

        val s3 = LinkHistoryPolicy.onConnections(s2, listOf(summary("a", LinkState.Unstable)), nowMs = 30)
        assertEquals(1, s3.connections.getValue("a").connects)
        assertEquals(0, s3.connections.getValue("a").drops)
    }

    @Test
    fun `a link going down counts a drop and clears the uptime`() {
        val up = LinkHistoryPolicy.onConnections(LinkHistoryState(), listOf(summary("a", LinkState.Connected)), nowMs = 20)
        val down = LinkHistoryPolicy.onConnections(up, listOf(summary("a", LinkState.Saved)), nowMs = 50)
        val history = down.connections.getValue("a")
        assertEquals(1, history.drops)
        assertNull(history.upSinceMs)
        assertEquals(50L, history.lastDropAtMs)
    }

    @Test
    fun `a binding remembers when it was made and resets when it moves host`() {
        val s1 = LinkHistoryPolicy.onBindings(LinkHistoryState(), mapOf("7" to "a"), nowMs = 100)
        assertEquals(100L, s1.boundSinceMs["7"])

        val s2 = LinkHistoryPolicy.onBindings(s1, mapOf("7" to "a"), nowMs = 200)
        assertEquals(100L, s2.boundSinceMs["7"])

        val s3 = LinkHistoryPolicy.onBindings(s2, mapOf("7" to "b"), nowMs = 300)
        assertEquals(300L, s3.boundSinceMs["7"])

        val s4 = LinkHistoryPolicy.onBindings(s3, emptyMap(), nowMs = 400)
        assertNull(s4.boundSinceMs["7"])
    }
}
