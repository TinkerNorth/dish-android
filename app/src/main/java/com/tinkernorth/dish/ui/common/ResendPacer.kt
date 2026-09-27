// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

/**
 * Pacing gate for the overlay resend loops. Real input is event-driven and
 * never passes through here; resends exist solely to heal a LOST edge: the
 * final frame of a gesture (button-up, finger-up, stick-to-neutral) that no
 * later frame would correct. A changed state is re-sent [EDGE_BURST_RESENDS]
 * ticks in a row, then falls back to a slow keepalive against pathological
 * multi-loss. Not thread-safe: call from the single resend thread only.
 */
class ResendPacer(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var burstLeft = 0
    private var lastSendNs = 0L

    fun resendDue(changed: Boolean): Boolean {
        val now = nanoTime()
        if (changed) {
            burstLeft = EDGE_BURST_RESENDS - 1
        } else if (burstLeft > 0) {
            burstLeft--
        } else if (now - lastSendNs < KEEPALIVE_INTERVAL_NS) {
            return false
        }
        lastSendNs = now
        return true
    }

    companion object {
        // A changed state is re-sent this many ticks in a row: one lost edge
        // frame heals at the next tick, a double loss at the one after.
        const val EDGE_BURST_RESENDS = 3
        const val KEEPALIVE_INTERVAL_NS = 1_000_000_000L

        // How far behind its clock the loop may fall before it stops catching up.
        const val MAX_BACKLOG_FACTOR = 5L
    }
}

// The loop's next deadline: kept while the loop is merely late, re-anchored to now once it
// has fallen more than MAX_BACKLOG_FACTOR intervals behind, so a stall never replays a burst
// of back-dated reports.
internal fun resendDeadlineFor(
    deadlineNs: Long,
    nowNs: Long,
    intervalNs: Long,
): Long {
    val backlogNs = nowNs - deadlineNs
    val runaway = backlogNs > intervalNs * ResendPacer.MAX_BACKLOG_FACTOR
    return if (runaway) nowNs + intervalNs else deadlineNs
}
