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
    }
}
