// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [TouchpadSurfaceView.TouchpadState.anyFingerDown].
 * Pure data-class predicate; trivial code but load-bearing for the
 * click-when-touched semantic ([TouchpadSurfaceView.updateButton]) and
 * the coordinator's start/end callbacks
 * ([TouchpadSurfaceView.onTouchEvent] / [TouchpadPadCoordinator]).
 *
 * If a future refactor splits finger0Active and finger1Active across
 * different state holders (e.g. per-slot StateFlow), this test should be
 * updated to wrap whatever predicate replaces it — but until then,
 * "any finger is in contact" is the central invariant the rest of the
 * touchpad stack hangs on.
 */
class TouchpadStateTest {
    @Test
    fun `default state reports no fingers down`() {
        val s = TouchpadSurfaceView.TouchpadState()
        assertFalse(s.anyFingerDown())
    }

    @Test
    fun `finger0 alone counts as a finger down`() {
        val s = TouchpadSurfaceView.TouchpadState(finger0Active = true)
        assertTrue(s.anyFingerDown())
    }

    @Test
    fun `finger1 alone counts as a finger down`() {
        // The single-finger case where the user touched with the index
        // finger, then lifted it, then touched with the middle finger
        // before the surface fully released. Slot 1 might be the only
        // active one mid-gesture; the click-when-touched semantic must
        // still assert.
        val s = TouchpadSurfaceView.TouchpadState(finger1Active = true)
        assertTrue(s.anyFingerDown())
    }

    @Test
    fun `both fingers down counts as a finger down`() {
        val s =
            TouchpadSurfaceView.TouchpadState(
                finger0Active = true,
                finger1Active = true,
            )
        assertTrue(s.anyFingerDown())
    }

    @Test
    fun `buttonPressed field is independent of finger state`() {
        // The wire flag bits are independent: a frame with
        // buttonPressed=true but no fingers active is unusual but
        // syntactically valid (e.g. a DS4 hold-then-lift-fingers
        // sequence at the receiver). Pin that anyFingerDown only
        // reads finger0Active / finger1Active.
        val s =
            TouchpadSurfaceView.TouchpadState(
                buttonPressed = true,
                finger0Active = false,
                finger1Active = false,
            )
        assertFalse(s.anyFingerDown())
    }

    @Test
    fun `eventTimeMs field defaults to zero and is independently settable`() {
        // The eventTimeMs field carries Android MotionEvent.getEventTime()
        // through to the wire; the satellite uses dt between consecutive
        // samples to time-scale the relative-mouse delta (fixes the
        // first-touch jump). Pin the default + independent set so a
        // future refactor of TouchpadState's field list doesn't drop it.
        val default = TouchpadSurfaceView.TouchpadState()
        assertEquals(0L, default.eventTimeMs)
        val set =
            TouchpadSurfaceView.TouchpadState(
                finger0Active = true,
                eventTimeMs = 1_234_567L,
            )
        assertEquals(1_234_567L, set.eventTimeMs)
        // Field is orthogonal to anyFingerDown's predicate.
        assertTrue(set.anyFingerDown())
    }
}
