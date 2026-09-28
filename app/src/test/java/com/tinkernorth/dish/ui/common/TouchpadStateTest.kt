// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val default = TouchpadSurfaceView.TouchpadState()
        assertEquals(0L, default.eventTimeMs)
        val set =
            TouchpadSurfaceView.TouchpadState(
                finger0Active = true,
                eventTimeMs = 1_234_567L,
            )
        assertEquals(1_234_567L, set.eventTimeMs)
        assertTrue(set.anyFingerDown())
    }

    // Every field differs from its default, so copying any one of them wrong shows.
    @Test
    fun `copyFrom takes every field of the other state in place`() {
        val source =
            TouchpadSurfaceView.TouchpadState(
                finger0Active = true,
                finger1Active = true,
                buttonPressed = true,
                finger0TrackingId = 7,
                finger0X = 1200,
                finger0Y = -300,
                finger1TrackingId = 8,
                finger1X = -1500,
                finger1Y = 900,
                eventTimeMs = 1_234_567L,
            )
        val target = TouchpadSurfaceView.TouchpadState()
        target.copyFrom(source)
        assertEquals(source, target)
    }
}
