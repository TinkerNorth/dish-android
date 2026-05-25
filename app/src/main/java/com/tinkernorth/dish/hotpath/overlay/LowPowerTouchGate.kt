// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.view.MotionEvent

// Keep consuming after the overlay is dismissed on DOWN, else Android still synthesises a click on the underlying view.
class LowPowerTouchGate {
    private var consumingGesture = false

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
