// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.overlay

import android.view.MotionEvent

/**
 * Decides whether `Activity.dispatchTouchEvent` should swallow a touch when
 * the low-power overlay is up.
 *
 * Two responsibilities, both of which the prior in-line check missed:
 *
 *  1. **Swallow the DOWN.** Without this, the dim overlay let the gesture
 *     pass through to whichever button happened to be underneath the user's
 *     finger.
 *  2. **Swallow the rest of the gesture, even after the overlay has already
 *     been dismissed.** Tapping ACTIVE → IDLE happens on the DOWN; if we
 *     stop consuming on the next MOVE/UP, Android still synthesises a click
 *     on the underlying view because the gesture started inside its bounds.
 *
 * Pure: no Android `MotionEvent` allocations are kept, no touching of the
 * window. The caller passes only the action int and whether the overlay is
 * currently up.
 */
class LowPowerTouchGate {
    private var consumingGesture = false

    /**
     * @param action one of [MotionEvent.ACTION_DOWN], `_MOVE`, `_UP`, `_CANCEL`, `_POINTER_*`.
     * @param overlayActive whether the dim overlay is currently visible.
     * @return true if `dispatchTouchEvent` should return true (consume).
     */
    fun onDispatch(
        action: Int,
        overlayActive: Boolean,
    ): Boolean {
        if (overlayActive) {
            consumingGesture = true
            return true
        }
        if (consumingGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                consumingGesture = false
            }
            return true
        }
        return false
    }
}
