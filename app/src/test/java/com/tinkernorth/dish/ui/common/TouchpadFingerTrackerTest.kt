// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The on-screen touchpad's slot bookkeeping, as TouchpadSurfaceView drives it: two fingers,
// slots assigned lowest-free and released by pointer id, a fresh tracking id per contact.
class TouchpadFingerTrackerTest {
    private val tracker = TouchpadFingerTracker()
    private val state get() = tracker.state

    private fun down(
        pointerId: Int,
        x: Float = 0f,
        y: Float = 0f,
        timeMs: Long = 1L,
    ): Boolean = tracker.fingerDown(pointerId, x, y, WIDTH, HEIGHT, timeMs)

    private fun move(
        pointerId: Int,
        x: Float,
        y: Float,
        timeMs: Long = 2L,
    ): Boolean = tracker.fingerMoved(pointerId, x, y, WIDTH, HEIGHT, timeMs)

    @Test
    fun `the first finger takes slot 0 and reports the surface as newly active`() {
        assertTrue(down(FIRST))
        assertTrue(state.finger0Active)
        assertFalse(state.finger1Active)
        assertEquals(0, state.finger0TrackingId)
    }

    @Test
    fun `a second finger takes slot 1 without re-reporting activity`() {
        down(FIRST)
        assertFalse(down(SECOND))
        assertTrue(state.finger1Active)
        assertEquals(1, state.finger1TrackingId)
    }

    @Test
    fun `a third finger is ignored`() {
        down(FIRST)
        down(SECOND)
        assertFalse(down(THIRD, x = WIDTH.toFloat(), y = HEIGHT.toFloat()))
        assertFalse(move(THIRD, x = WIDTH.toFloat(), y = HEIGHT.toFloat()))
        assertEquals(Short.MIN_VALUE, state.finger0X)
        assertEquals(Short.MIN_VALUE, state.finger1X)
    }

    @Test
    fun `a finger already down keeps its slot on a repeated down`() {
        down(FIRST)
        assertFalse(down(FIRST))
        assertEquals(0, state.finger0TrackingId)
        assertFalse(state.finger1Active)
    }

    @Test
    fun `releasing one finger frees only its slot and keeps the other's position`() {
        down(FIRST)
        down(SECOND, x = WIDTH.toFloat(), y = HEIGHT.toFloat())

        assertFalse(tracker.fingerUp(FIRST))

        assertFalse(state.finger0Active)
        assertEquals(0.toShort(), state.finger0X)
        assertTrue(state.finger1Active)
        assertEquals(Short.MAX_VALUE, state.finger1X)
        assertEquals(Short.MAX_VALUE, state.finger1Y)
    }

    @Test
    fun `lifting the last finger reports the surface clear`() {
        down(FIRST)
        assertTrue(tracker.fingerUp(FIRST))
        assertFalse(state.anyFingerDown())
    }

    @Test
    fun `lifting a pointer that never landed changes nothing`() {
        down(FIRST)
        assertFalse(tracker.fingerUp(THIRD))
        assertTrue(state.finger0Active)
    }

    @Test
    fun `a freed slot is reused with a fresh tracking id`() {
        down(FIRST)
        down(SECOND)
        tracker.fingerUp(FIRST)

        down(THIRD)

        assertTrue(state.finger0Active)
        assertEquals(2, state.finger0TrackingId)
    }

    @Test
    fun `tracking ids wrap at the mask`() {
        for (contact in 0..TRACKING_ID_WRAP_MASK) {
            down(contact)
            tracker.fingerUp(contact)
        }
        down(FIRST)
        assertEquals(0, state.finger0TrackingId)
    }

    @Test
    fun `a move for a pointer that is not ours changes nothing`() {
        down(FIRST)
        assertFalse(move(SECOND, x = WIDTH.toFloat(), y = 0f))
        assertEquals(Short.MIN_VALUE, state.finger0X)
    }

    @Test
    fun `a move for our finger updates its position and the frame time`() {
        down(FIRST)
        assertTrue(move(FIRST, x = WIDTH.toFloat(), y = HEIGHT.toFloat(), timeMs = MOVE_TIME_MS))
        assertEquals(Short.MAX_VALUE, state.finger0X)
        assertEquals(Short.MAX_VALUE, state.finger0Y)
        assertEquals(MOVE_TIME_MS, state.eventTimeMs)
    }

    @Test
    fun `a finger past the view edge pins to the edge`() {
        down(FIRST, x = -10f, y = HEIGHT * 2f)
        assertEquals(Short.MIN_VALUE, state.finger0X)
        assertEquals(Short.MAX_VALUE, state.finger0Y)
    }

    @Test
    fun `a zero-sized view does not divide by zero`() {
        tracker.fingerDown(FIRST, 0f, 0f, 0, 0, 1L)
        assertEquals(Short.MIN_VALUE, state.finger0X)
        assertEquals(Short.MIN_VALUE, state.finger0Y)
    }

    @Test
    fun `the click follows the fingers while clickWhenTouched is on`() {
        down(FIRST)
        assertTrue(state.buttonPressed)
        tracker.fingerUp(FIRST)
        assertFalse(state.buttonPressed)
    }

    @Test
    fun `with clickWhenTouched off the click stays released`() {
        tracker.clickWhenTouched = false
        down(FIRST)
        assertFalse(state.buttonPressed)
    }

    @Test
    fun `flipping clickWhenTouched mid-touch updates the click at once`() {
        down(FIRST)
        tracker.clickWhenTouched = false
        assertFalse(state.buttonPressed)
        tracker.clickWhenTouched = true
        assertTrue(state.buttonPressed)
    }

    @Test
    fun `liftAll clears both fingers, their positions and the click`() {
        down(FIRST, x = WIDTH.toFloat(), y = HEIGHT.toFloat())
        down(SECOND, x = WIDTH.toFloat(), y = HEIGHT.toFloat())

        tracker.liftAll()

        assertFalse(state.anyFingerDown())
        assertFalse(state.buttonPressed)
        assertEquals(0.toShort(), state.finger0X)
        assertEquals(0.toShort(), state.finger1Y)
    }

    @Test
    fun `after liftAll a returning pointer id takes a fresh slot and id`() {
        down(FIRST)
        down(SECOND)
        tracker.liftAll()

        assertTrue(down(FIRST))

        assertTrue(state.finger0Active)
        assertFalse(state.finger1Active)
        assertEquals(2, state.finger0TrackingId)
    }

    // ---- the lift the view hands over: UP, POINTER_UP or CANCEL ----

    @Test
    fun `a pointer up with another finger down leaves the gesture running`() {
        down(FIRST)
        down(SECOND)

        assertEquals(TouchpadLift.FINGERS_REMAIN, tracker.fingerLifted(MotionEvent.ACTION_POINTER_UP, FIRST))
        assertTrue(state.finger1Active)
    }

    @Test
    fun `an up of the last finger ends the gesture as a lift`() {
        down(FIRST)

        assertEquals(TouchpadLift.LAST_FINGER_LIFTED, tracker.fingerLifted(MotionEvent.ACTION_UP, FIRST))
    }

    @Test
    fun `a cancel with two fingers down lifts both fingers and the click`() {
        down(FIRST, x = WIDTH.toFloat(), y = HEIGHT.toFloat())
        down(SECOND, x = WIDTH.toFloat(), y = HEIGHT.toFloat())

        val lift = tracker.fingerLifted(MotionEvent.ACTION_CANCEL, FIRST)

        assertEquals(TouchpadLift.CANCELLED, lift)
        assertFalse(state.finger0Active)
        assertFalse(state.finger1Active)
        assertFalse(state.buttonPressed)
        assertEquals(0.toShort(), state.finger1X)
    }

    @Test
    fun `a cancel with one finger down ends the gesture without a click`() {
        down(FIRST)

        assertEquals(TouchpadLift.CANCELLED, tracker.fingerLifted(MotionEvent.ACTION_CANCEL, FIRST))
        assertFalse(state.anyFingerDown())
    }

    @Test
    fun `a cancel on an idle surface still ends the gesture`() {
        assertEquals(TouchpadLift.CANCELLED, tracker.fingerLifted(MotionEvent.ACTION_CANCEL, FIRST))
    }

    @Test
    fun `after a cancel a returning pointer takes slot 0 again`() {
        down(FIRST)
        down(SECOND)
        tracker.fingerLifted(MotionEvent.ACTION_CANCEL, FIRST)

        assertTrue(down(SECOND))

        assertTrue(state.finger0Active)
        assertFalse(state.finger1Active)
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 100
        const val FIRST = 3
        const val SECOND = 5
        const val THIRD = 9
        const val MOVE_TIME_MS = 77L
    }
}
