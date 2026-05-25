// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = true))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_POINTER_DOWN, overlayActive = true))
    }

    @Test
    fun `gesture started while active stays consumed even after overlay dismisses`() {
        // Must keep swallowing until gesture ends, else underlying button sees synthesised UP click.
        assertTrue(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true))

        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false))
    }

    @Test
    fun `UP terminates the consumed gesture so the next gesture is not swallowed`() {
        gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true)
        gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false)

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
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_MOVE, overlayActive = false))
    }

    @Test
    fun `consecutive overlay-active gestures each consume their full lifecycle`() {
        gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true)
        gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false)

        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))

        assertTrue(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = true))
        assertTrue(gate.onDispatch(MotionEvent.ACTION_UP, overlayActive = false))
        assertFalse(gate.onDispatch(MotionEvent.ACTION_DOWN, overlayActive = false))
    }
}
