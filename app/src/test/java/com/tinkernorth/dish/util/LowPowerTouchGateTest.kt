// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.util

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract for [LowPowerTouchGate]. The gate decides whether a
 * dispatched touch should be consumed by the activity instead of falling
 * through to the regular view tree while (or just after) the dim overlay is
 * up.
 *
 * Regression that motivated the gate: without the post-DOWN gesture lock, a
 * tap on the dim overlay dismissed the overlay on the DOWN, then the
 * subsequent UP synthesised a click on whichever button was underneath.
 */
class LowPowerTouchGateTest {
    private val gate = LowPowerTouchGate()

    @Test
    fun `overlay inactive and no prior gesture passes the touch through`() {
        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))
        assertFalse(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertFalse(gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false))
    }

    @Test
    fun `overlay active consumes the DOWN`() {
        assertTrue(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true))
    }

    @Test
    fun `overlay active consumes any action mid-gesture`() {
        // The Activity may report any phase of a gesture while the overlay is
        // up — they all need to be swallowed regardless.
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = true))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_POINTER_DOWN, overlayActive = true))
    }

    @Test
    fun `gesture started while active stays consumed even after overlay dismisses`() {
        // ACTIVE → IDLE happens on this DOWN. The gate must keep swallowing
        // until the gesture really ends, otherwise the underlying button sees
        // the synthesised click on UP.
        assertTrue(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true))

        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false))
    }

    @Test
    fun `UP terminates the consumed gesture so the next gesture is not swallowed`() {
        gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true)
        gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false)

        // Fresh tap with the overlay long gone — must not consume.
        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))
    }

    @Test
    fun `CANCEL also terminates the consumed gesture`() {
        gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true)
        gate.onDispatch(MotionEvent.ACTION_CANCEL, overlayActive = false)

        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))
    }

    @Test
    fun `MOVE alone does not terminate the consumed gesture`() {
        gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true)
        // Several moves and the overlay has long gone — still consumed because
        // the gesture has not been ended by UP/CANCEL yet.
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
    }

    @Test
    fun `consecutive overlay-active gestures each consume their full lifecycle`() {
        gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true)
        gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false)

        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))

        // Overlay re-enters during a quiet period, next tap is consumed again.
        assertTrue(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false))
        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))
    }
}
