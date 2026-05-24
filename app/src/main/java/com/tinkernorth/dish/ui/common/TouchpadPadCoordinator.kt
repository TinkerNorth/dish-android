// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

/**
 * Mutual-exclusion coordinator for the touchpad overlay's pair of
 * [TouchpadSurfaceView]s (the Click + Move pads).
 *
 * The MSG_TOUCHPAD wire format carries one finger-0 per packet — and the
 * satellite's mouse mode only routes finger 0 — so two simultaneously
 * emitting pads would race to overwrite each other's coordinates. This
 * coordinator pins ownership to whichever pad's first finger landed
 * first, locks out the other, and releases on lift.
 *
 * Pads are identified by any unique token (typically the
 * [TouchpadSurfaceView] instance itself, compared with `===`). The
 * coordinator does not hold strong references to ensure pads can be GC'd
 * with the activity — store any [T] you want, but keep one stable
 * reference per pad for the duration of the overlay.
 *
 * Not thread-safe by itself: callers are expected to invoke from the main
 * (input) thread. The activity already serialises touch dispatch through
 * the Android input pipeline, so adding a lock here would be ceremony.
 *
 * Extracted from [TouchpadOverlayActivity] so the lock semantics can be
 * pinned by unit tests without instantiating the surface view (which
 * needs a real [android.content.Context]) — same pattern as
 * [GamepadGestureRecognizer] sitting alongside [GamepadTouchView].
 */
class TouchpadPadCoordinator<T : Any> {
    private var owner: T? = null

    /** The pad currently holding the lock, or null if neither has any fingers down. */
    fun active(): T? = owner

    /**
     * Mark [pad] as having transitioned from "no fingers" to "any finger
     * down." Returns true if [pad] just claimed ownership of the wire
     * (the caller should lock the other pad), false if another pad
     * already owns it (the caller should leave this pad's touches
     * unforwarded). Calling this twice in a row for the same pad is a
     * no-op — only the first transition matters.
     */
    fun onTouchStart(pad: T): Boolean {
        if (owner != null) {
            // Two pads could land an ACTION_DOWN on the same input frame
            // in theory; in practice the Android input dispatcher
            // serialises them. The first one through claims; the second
            // gets locked. Returning false here means the caller knows
            // not to update the shared write cache from this pad.
            return owner === pad
        }
        owner = pad
        return true
    }

    /**
     * Mark [pad] as having transitioned from "any finger down" to "no
     * fingers." Returns true if [pad] was the owner and the lock has now
     * been released (the caller should unlock the other pad), false if
     * [pad] wasn't the owner (no-op, the lock stays with whoever has it).
     */
    fun onTouchEnd(pad: T): Boolean {
        if (owner !== pad) return false
        owner = null
        return true
    }

    /**
     * Whether [pad] is allowed to write to the shared resend cache /
     * forward state on the wire. True iff no pad currently owns the
     * lock (initial state) OR [pad] is the owner. The "no owner"
     * fallthrough matters for the lift-emission tick — the active pad's
     * final state-changed callback fires AFTER its onTouchActivityChanged
     * has flipped owner back to null, so we still want that final
     * (all-zero) state to reach the receiver as the clean lift signal.
     */
    fun mayWrite(pad: T): Boolean = owner == null || owner === pad
}
