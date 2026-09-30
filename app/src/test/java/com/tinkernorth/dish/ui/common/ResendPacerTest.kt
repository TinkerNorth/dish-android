// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResendPacerTest {
    private val tickNs = 50_000_000L // the overlays' scheduler interval

    private var nowNs = 1_000_000_000L // non-zero so "never sent" ≠ "sent at t=0"
    private val pacer = ResendPacer { nowNs }

    // One scheduler tick: advance the fake clock, then ask the gate.
    private fun tick(changed: Boolean): Boolean {
        nowNs += tickNs
        return pacer.resendDue(changed)
    }

    @Test
    fun `a change sends immediately plus the rest of the edge burst, then goes quiet`() {
        assertTrue(tick(changed = true))
        // EDGE_BURST_RESENDS sends total: the change tick + two unchanged ticks.
        assertTrue(tick(changed = false))
        assertTrue(tick(changed = false))
        assertFalse(tick(changed = false))
        assertFalse(tick(changed = false))
    }

    @Test
    fun `steady state sends exactly one keepalive per interval`() {
        assertTrue(tick(changed = true))
        repeat(ResendPacer.EDGE_BURST_RESENDS - 1) { assertTrue(tick(changed = false)) }

        var sends = 0
        // Two keepalive intervals of unchanged ticks → exactly two sends.
        val ticks = (2 * ResendPacer.KEEPALIVE_INTERVAL_NS / tickNs).toInt()
        repeat(ticks) { if (tick(changed = false)) sends++ }
        assertEquals(2, sends)
    }

    @Test
    fun `a change mid-burst restarts the burst from that tick`() {
        assertTrue(tick(changed = true))
        assertTrue(tick(changed = false)) // burst tick 2 of 3
        assertTrue(tick(changed = true)) // new change, burst restarts
        assertTrue(tick(changed = false))
        assertTrue(tick(changed = false))
        assertFalse(tick(changed = false))
    }

    @Test
    fun `keepalive clock restarts from the last burst send`() {
        assertTrue(tick(changed = true))
        repeat(ResendPacer.EDGE_BURST_RESENDS - 1) { assertTrue(tick(changed = false)) }
        val lastBurstSendNs = nowNs

        // Just short of one keepalive interval after the LAST burst send: quiet.
        nowNs = lastBurstSendNs + ResendPacer.KEEPALIVE_INTERVAL_NS - 2 * tickNs
        assertFalse(tick(changed = false))
        // The next tick crosses the interval: one keepalive send.
        assertTrue(tick(changed = false))
    }

    // The resend loop's deadline: kept while merely late, re-anchored once the backlog is
    // runaway, so a stalled thread never replays a burst of back-dated reports.
    @Test
    fun `a deadline within the backlog window is kept`() {
        val deadlineNs = nowNs
        val lateBy = ResendPacer.MAX_BACKLOG_FACTOR * tickNs
        assertEquals(deadlineNs, resendDeadlineFor(deadlineNs, nowNs = deadlineNs + lateBy, intervalNs = tickNs))
    }

    @Test
    fun `a deadline still in the future is kept`() {
        val deadlineNs = nowNs + tickNs
        assertEquals(deadlineNs, resendDeadlineFor(deadlineNs, nowNs = nowNs, intervalNs = tickNs))
    }

    @Test
    fun `a runaway backlog re-anchors the deadline one interval past now`() {
        val deadlineNs = nowNs
        val runawayNow = deadlineNs + ResendPacer.MAX_BACKLOG_FACTOR * tickNs + 1
        assertEquals(runawayNow + tickNs, resendDeadlineFor(deadlineNs, nowNs = runawayNow, intervalNs = tickNs))
    }
}
