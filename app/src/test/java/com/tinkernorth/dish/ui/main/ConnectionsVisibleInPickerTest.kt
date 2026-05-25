// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsVisibleInPickerTest {
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
    fun `Connected counts as available`() {
        assertTrue(LinkState.Connected.isAvailableForPicker())
    }

    @Test
    fun `Unstable counts as available — link is faltering but still usable`() {
        assertTrue(LinkState.Unstable.isAvailableForPicker())
    }

    @Test
    fun `Connecting counts as available`() {
        assertTrue(LinkState.Connecting.isAvailableForPicker())
    }

    @Test
    fun `Ready counts as available — paired and seen, just no session yet`() {
        assertTrue(LinkState.Ready.isAvailableForPicker())
    }

    @Test
    fun `Found counts as available — visible target, just unpaired`() {
        assertTrue(LinkState.Found.isAvailableForPicker())
    }

    @Test
    fun `Saved does not count as available — offline`() {
        assertFalse(LinkState.Saved.isAvailableForPicker())
    }

    @Test
    fun `Stale does not count as available — needs re-pairing`() {
        assertFalse(LinkState.Stale.isAvailableForPicker())
    }

    @Test
    fun `every LinkState resolves through the predicate without throwing`() {
        for (state in LinkState.entries) state.isAvailableForPicker()
    }

    @Test
    fun `empty input returns empty list regardless of bind state`() {
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(emptyList(), null))
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(emptyList(), "s:anything"))
    }

    @Test
    fun `bound id that does not appear in the list is a no-op`() {
        val online = summary("s:1", LinkState.Connected)
        val visible = connectionsVisibleInPicker(listOf(online), boundConnectionId = "s:gone")
        assertEquals(listOf(online), visible)
    }

    @Test
    fun `bound id of empty string matches nothing`() {
        val saved = summary("s:1", LinkState.Saved)
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(saved), ""))
    }

    @Test
    fun `spec — unavailable unbound connection is hidden`() {
        val offline = summary("s:1", LinkState.Saved)
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(offline), null))
    }

    @Test
    fun `spec — unavailable bound connection stays visible as the holdover`() {
        val bound = summary("s:1", LinkState.Saved)
        assertEquals(listOf(bound), connectionsVisibleInPicker(listOf(bound), "s:1"))
    }

    @Test
    fun `spec — unbinding an offline connection makes it disappear`() {
        val offline = summary("s:1", LinkState.Saved)
        assertEquals(listOf(offline), connectionsVisibleInPicker(listOf(offline), "s:1"))
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(offline), null))
    }

    @Test
    fun `spec — auto-recovery brings the row back to a normal available render`() {
        val offlineBound = summary("s:1", LinkState.Saved)
        val recoveredBound = summary("s:1", LinkState.Connected)

        val whileOffline = connectionsVisibleInPicker(listOf(offlineBound), boundConnectionId = "s:1")
        assertEquals(listOf(offlineBound), whileOffline)

        val afterRecovery = connectionsVisibleInPicker(listOf(recoveredBound), boundConnectionId = "s:1")
        assertEquals(listOf(recoveredBound), afterRecovery)
    }

    @Test
    fun `spec — Stale bound holdover is preserved until user re-pairs or unbinds`() {
        val stale = summary("s:1", LinkState.Stale)
        assertEquals(listOf(stale), connectionsVisibleInPicker(listOf(stale), "s:1"))
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(stale), null))
    }

    @Test
    fun `picker drops only the unreachable unbound entries`() {
        val online = summary("s:online", LinkState.Connected)
        val ready = summary("s:ready", LinkState.Ready)
        val offline = summary("s:offline", LinkState.Saved)
        val needsPair = summary("s:stale", LinkState.Stale)

        val visible =
            connectionsVisibleInPicker(
                listOf(online, ready, offline, needsPair),
                boundConnectionId = null,
            )

        assertEquals(listOf(online, ready), visible)
    }

    @Test
    fun `picker shows the bound offline entry alongside other available ones`() {
        val onlineAlt = summary("s:online", LinkState.Connected)
        val boundOffline = summary("s:bound", LinkState.Saved)
        val unboundOffline = summary("s:other", LinkState.Saved)

        val visible =
            connectionsVisibleInPicker(
                listOf(onlineAlt, boundOffline, unboundOffline),
                boundConnectionId = "s:bound",
            )

        assertEquals(listOf(onlineAlt, boundOffline), visible)
    }

    @Test
    fun `picker preserves the input order`() {
        val a = summary("s:1", LinkState.Connected)
        val b = summary("s:2", LinkState.Connected)
        val c = summary("s:3", LinkState.Connected)

        val visible = connectionsVisibleInPicker(listOf(a, b, c), boundConnectionId = "s:3")

        assertEquals(listOf(a, b, c), visible)
    }

    @Test
    fun `picker preserves order even with mixed available and held-over rows`() {
        val a = summary("s:a", LinkState.Connected)
        val bSaved = summary("s:b", LinkState.Saved)
        val c = summary("s:c", LinkState.Connecting)
        val dSavedUnbound = summary("s:d", LinkState.Saved)
        val e = summary("s:e", LinkState.Ready)

        val visible =
            connectionsVisibleInPicker(
                listOf(a, bSaved, c, dSavedUnbound, e),
                boundConnectionId = "s:b",
            )

        assertEquals(listOf(a, bSaved, c, e), visible)
    }

    @Test
    fun `picker is kind-agnostic — both BT and Satellite obey the same rule`() {
        val satOnline = summary("s:sat", LinkState.Connected, ConnectionKind.SATELLITE)
        val satOffline = summary("s:sat-off", LinkState.Saved, ConnectionKind.SATELLITE)
        val btOnline = summary("bt:on", LinkState.Connected, ConnectionKind.BLUETOOTH)
        val btOffline = summary("bt:off", LinkState.Saved, ConnectionKind.BLUETOOTH)

        val visible =
            connectionsVisibleInPicker(
                listOf(satOnline, satOffline, btOnline, btOffline),
                boundConnectionId = null,
            )

        assertEquals(listOf(satOnline, btOnline), visible)
    }

    @Test
    fun `picker preserves a Bluetooth bound holdover the same way as Satellite`() {
        val btOffline = summary("bt:1", LinkState.Saved, ConnectionKind.BLUETOOTH)
        val satOnline = summary("s:1", LinkState.Connected, ConnectionKind.SATELLITE)

        val visible = connectionsVisibleInPicker(listOf(btOffline, satOnline), boundConnectionId = "bt:1")
        assertEquals(listOf(btOffline, satOnline), visible)
    }

    @Test
    fun `picker keeps exactly one held-over row even when multiple are unreachable`() {
        val offlineConns = (1..6).map { summary("s:$it", LinkState.Saved) }

        val visible = connectionsVisibleInPicker(offlineConns, boundConnectionId = "s:4")

        assertEquals(listOf(summary("s:4", LinkState.Saved)), visible)
    }

    @Test
    fun `filter does not mutate the input list`() {
        val input =
            mutableListOf(
                summary("s:1", LinkState.Connected),
                summary("s:2", LinkState.Saved),
                summary("s:3", LinkState.Stale),
            )
        val snapshot = input.toList()

        connectionsVisibleInPicker(input, boundConnectionId = "s:2")

        assertEquals(snapshot, input)
    }

    @Test
    fun `filter does not share its result with the input when the result is smaller`() {
        val input =
            listOf(
                summary("s:1", LinkState.Connected),
                summary("s:2", LinkState.Saved),
            )
        val result = connectionsVisibleInPicker(input, boundConnectionId = null)
        assertNotSame(input, result)
    }

    @Test
    fun `filter is idempotent — running it twice on its own output is stable`() {
        val input =
            listOf(
                summary("s:1", LinkState.Connected),
                summary("s:2", LinkState.Saved),
                summary("s:3", LinkState.Stale),
                summary("s:4", LinkState.Ready),
            )
        val once = connectionsVisibleInPicker(input, boundConnectionId = "s:2")
        val twice = connectionsVisibleInPicker(once, boundConnectionId = "s:2")
        assertEquals(once, twice)
    }

    @Test
    fun `filter preserves the ConnectionSummary identity of each surviving item`() {
        val a = summary("s:1", LinkState.Connected)
        val b = summary("s:2", LinkState.Saved)
        val result = connectionsVisibleInPicker(listOf(a, b), boundConnectionId = "s:2")
        assertSame(a, result[0])
        assertSame(b, result[1])
    }

    @Test
    fun `cross product of state and bind status matches the spec table`() {
        for (state in LinkState.entries) {
            val c = summary("s:probe", state)

            val whenBound = connectionsVisibleInPicker(listOf(c), boundConnectionId = "s:probe")
            assertEquals("bound + $state should always be visible", listOf(c), whenBound)

            val whenUnbound = connectionsVisibleInPicker(listOf(c), boundConnectionId = null)
            val expected = if (state.isAvailableForPicker()) listOf(c) else emptyList()
            assertEquals("unbound + $state should follow availability", expected, whenUnbound)
        }
    }
}
