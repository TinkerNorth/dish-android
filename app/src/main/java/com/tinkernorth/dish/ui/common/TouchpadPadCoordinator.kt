// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

// MSG_TOUCHPAD carries only finger 0, so two simultaneously emitting pads would race; this pins ownership to whichever pad's first finger landed first.
class TouchpadPadCoordinator<T : Any> {
    private var owner: T? = null

    fun active(): T? = owner

    fun onTouchStart(pad: T): Boolean {
        if (owner != null) {
            return owner === pad
        }
        owner = pad
        return true
    }

    fun onTouchEnd(pad: T): Boolean {
        if (owner !== pad) return false
        owner = null
        return true
    }

    // The "no owner" fallthrough lets the active pad's final all-zero state reach the receiver as the clean lift signal after ownership clears.
    fun mayWrite(pad: T): Boolean = owner == null || owner === pad
}
