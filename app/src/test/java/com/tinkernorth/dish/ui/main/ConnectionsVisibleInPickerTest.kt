// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

/**
 * Tests for the slot picker's visibility rule (see
 * [connectionsVisibleInPicker]) and its underlying state predicate
 * [LinkState.isAvailableForPicker].
 *
 * Spec the picker enforces:
 *   1. An "available" connection (online, connecting, ready, found, unstable)
 *      always shows in the picker.
 *   2. An "unreachable" one (offline, needs-pairing) shows only when the slot
 *      is currently bound to it — so the user has a hand-off until either
 *      auto-reconnect recovers it or they unbind explicitly.
 *   3. Once unbound, an unreachable connection disappears from the picker.
 *
 * These tests pin the cross-product so a future [LinkState] addition (or any
 * change to the filter call site) surfaces here.
 */
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

    // ── isAvailableForPicker — state predicate ──────────────────────────────

    @Test
    fun `Connected counts as available`() {
        assertTrue(LinkState.Connected.isAvailableForPicker())
    }

    @Test
    fun `Unstable counts as available — link is faltering but still usable`() {
        // Unstable still routes packets; the chip is "Unsteady" not gone. The
        // picker should keep it claimable so the user isn't surprised by it
        // appearing/disappearing as the link wobbles.
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
        // Exhaustiveness lock: a new LinkState added without updating the
        // when() in isAvailableForPicker would either fail to compile (good)
        // or fall off the when (would throw NoWhenBranchMatchedException).
        // This guards the runtime side just in case.
        for (state in LinkState.entries) state.isAvailableForPicker()
    }

    // ── connectionsVisibleInPicker — empty / degenerate cases ───────────────

    @Test
    fun `empty input returns empty list regardless of bind state`() {
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(emptyList(), null))
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(emptyList(), "s:anything"))
    }

    @Test
    fun `bound id that does not appear in the list is a no-op`() {
        // Mirrors a dangling binding after a forget — the picker just renders
        // whatever's left, no special-case row injection. The slot header in
        // the parent card still reflects the bound id.
        val online = summary("s:1", LinkState.Connected)
        val visible = connectionsVisibleInPicker(listOf(online), boundConnectionId = "s:gone")
        assertEquals(listOf(online), visible)
    }

    @Test
    fun `bound id of empty string matches nothing`() {
        // Defensive: there's no contract preventing a caller from passing "",
        // and ConnectionSummary.id is never "" in practice, so the filter
        // should treat it like null (no holdover row).
        val saved = summary("s:1", LinkState.Saved)
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(saved), ""))
    }

    // ── Spec scenarios — direct translation of the user's requirements ──────

    @Test
    fun `spec — unavailable unbound connection is hidden`() {
        // "When a connection isn't available, it should not be in the list of
        // available connections in my gamepad card."
        val offline = summary("s:1", LinkState.Saved)
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(offline), null))
    }

    @Test
    fun `spec — unavailable bound connection stays visible as the holdover`() {
        // "If I was already bound to it, then it should stay visible with a
        // disconnected visual representation..."
        val bound = summary("s:1", LinkState.Saved)
        assertEquals(listOf(bound), connectionsVisibleInPicker(listOf(bound), "s:1"))
    }

    @Test
    fun `spec — unbinding an offline connection makes it disappear`() {
        // "If the user unbinds and it is still disconnected, it should
        // disappear."
        val offline = summary("s:1", LinkState.Saved)
        // While bound: present.
        assertEquals(listOf(offline), connectionsVisibleInPicker(listOf(offline), "s:1"))
        // After unbind: gone.
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(offline), null))
    }

    @Test
    fun `spec — auto-recovery brings the row back to a normal available render`() {
        // "...until the connection automatically recovers..."
        // Simulate the lifecycle: bound → Saved (held over) → Connected (back).
        // The held-over row and the recovered row are both visible; only the
        // visual styling differs and that's the adapter's job, not the filter.
        val offlineBound = summary("s:1", LinkState.Saved)
        val recoveredBound = summary("s:1", LinkState.Connected)

        val whileOffline = connectionsVisibleInPicker(listOf(offlineBound), boundConnectionId = "s:1")
        assertEquals(listOf(offlineBound), whileOffline)

        val afterRecovery = connectionsVisibleInPicker(listOf(recoveredBound), boundConnectionId = "s:1")
        assertEquals(listOf(recoveredBound), afterRecovery)
    }

    @Test
    fun `spec — Stale bound holdover is preserved until user re-pairs or unbinds`() {
        // Stale ("needs pairing") is a flavor of unreachable — the server
        // forgot our pairing. The user fixing it by re-pairing returns the
        // row to Ready/Connected (covered by recovery test); explicit unbind
        // makes it disappear.
        val stale = summary("s:1", LinkState.Stale)
        assertEquals(listOf(stale), connectionsVisibleInPicker(listOf(stale), "s:1"))
        assertEquals(emptyList<ConnectionSummary>(), connectionsVisibleInPicker(listOf(stale), null))
    }

    // ── Multi-connection scenarios ─────────────────────────────────────────

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
        // The user's bound satellite drops offline while another is still
        // online — both must show, with the bound one acting as a hand-off
        // and the other available for re-binding.
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
        // The Connections page renders satellites then Bluetooth — the picker
        // should keep that ordering rather than (say) hoisting the bound row
        // to the top, so a user moving between screens isn't disoriented.
        val a = summary("s:1", LinkState.Connected)
        val b = summary("s:2", LinkState.Connected)
        val c = summary("s:3", LinkState.Connected)

        val visible = connectionsVisibleInPicker(listOf(a, b, c), boundConnectionId = "s:3")

        assertEquals(listOf(a, b, c), visible)
    }

    @Test
    fun `picker preserves order even with mixed available and held-over rows`() {
        // bound (held over) shows in its original slot, not floated.
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
        // Both a satellite and a Bluetooth host can be in the same picker;
        // the filter rule depends only on live state, not kind.
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
        // The boundConnectionId is a single id; even if six remembered
        // satellites are offline, only the one the slot is bound to should
        // be kept as a holdover.
        val offlineConns = (1..6).map { summary("s:$it", LinkState.Saved) }

        val visible = connectionsVisibleInPicker(offlineConns, boundConnectionId = "s:4")

        assertEquals(listOf(summary("s:4", LinkState.Saved)), visible)
    }

    // ── Invariants ──────────────────────────────────────────────────────────

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
        // When something is filtered out, the returned list must be a fresh
        // copy — callers must be free to mutate either without affecting the
        // other.
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
        // Useful property if a future refactor pipelines two filters together
        // or accidentally double-applies on a re-emit.
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
        // No defensive-copying of ConnectionSummary — the returned references
        // are the same objects the caller passed in, so downstream identity
        // checks (e.g. DiffUtil) behave correctly.
        val a = summary("s:1", LinkState.Connected)
        val b = summary("s:2", LinkState.Saved)
        val result = connectionsVisibleInPicker(listOf(a, b), boundConnectionId = "s:2")
        assertSame(a, result[0])
        assertSame(b, result[1])
    }

    // ── Cross product — (state × bound to this id?) ─────────────────────────

    @Test
    fun `cross product of state and bind status matches the spec table`() {
        // Build a connection in every state and check both bound + unbound
        // cases. Failing rows print the offending state so a regression's
        // origin is obvious from the failure message alone.
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
