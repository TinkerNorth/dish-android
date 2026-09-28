// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_LEFT
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_MIDDLE
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_RIGHT
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The mouse surface's frames as Moonlight's edge-triggered packets.
class MoonlightMouseMoverTest {
    private val mover = MoonlightMouseMover()

    private fun lifted() = TouchpadSurfaceView.TouchpadState()

    private fun finger(
        x: Int,
        y: Int,
        trackingId: Int = 1,
    ) = TouchpadSurfaceView.TouchpadState(
        finger0Active = true,
        finger0TrackingId = trackingId,
        finger0X = x.toShort(),
        finger0Y = y.toShort(),
    )

    private fun frame(
        fingers: TouchpadSurfaceView.TouchpadState = lifted(),
        scrollNotches: Int = 0,
        left: Boolean = false,
        right: Boolean = false,
        middle: Boolean = false,
    ) = mover.onFrame(fingers, scrollNotches, left, right, middle)

    @Test
    fun `a button is sent on its edges only`() {
        assertEquals(listOf(MouseCommand.Button(true, MOUSE_BUTTON_LEFT)), frame(left = true))
        assertTrue(frame(left = true).isEmpty())
        assertEquals(listOf(MouseCommand.Button(false, MOUSE_BUTTON_LEFT)), frame(left = false))
    }

    @Test
    fun `the three buttons go out left, right, middle`() {
        assertEquals(
            listOf(
                MouseCommand.Button(true, MOUSE_BUTTON_LEFT),
                MouseCommand.Button(true, MOUSE_BUTTON_RIGHT),
                MouseCommand.Button(true, MOUSE_BUTTON_MIDDLE),
            ),
            frame(left = true, right = true, middle = true),
        )
    }

    @Test
    fun `scroll notches become wheel units and clamp to int16`() {
        assertEquals(listOf(MouseCommand.Scroll(WHEEL_UNITS_PER_NOTCH)), frame(scrollNotches = 1))
        assertEquals(listOf(MouseCommand.Scroll(-WHEEL_UNITS_PER_NOTCH)), frame(scrollNotches = -1))
        assertEquals(listOf(MouseCommand.Scroll(Short.MAX_VALUE.toInt())), frame(scrollNotches = 1000))
        assertEquals(listOf(MouseCommand.Scroll(Short.MIN_VALUE.toInt())), frame(scrollNotches = -1000))
        assertEquals(Short.MAX_VALUE.toInt(), wheelDeltaFor(1000))
    }

    @Test
    fun `a fresh touch anchors without moving`() {
        assertTrue(frame(finger(1000, 1000)).isEmpty())
    }

    @Test
    fun `a drag moves relative to the anchor`() {
        frame(finger(0, 0))
        assertEquals(listOf(MouseCommand.MoveRel(27, 0)), frame(finger(1000, 0)))
    }

    @Test
    fun `slow drags accumulate until a whole pixel`() {
        frame(finger(0, 0))
        assertTrue(frame(finger(10, 0)).isEmpty())
        assertTrue(frame(finger(20, 0)).isEmpty())
        assertEquals(listOf(MouseCommand.MoveRel(1, 0)), frame(finger(40, 0)))
    }

    // Each 1000-unit step is 27.47 host pixels: whole pixels go out and the fractions add up to
    // an extra pixel on the third step.
    @Test
    fun `the remainder carries across moves instead of being dropped`() {
        frame(finger(0, 0))
        frame(finger(1000, 0))
        frame(finger(2000, 0))
        assertEquals(listOf(MouseCommand.MoveRel(28, 0)), frame(finger(3000, 0)))
    }

    @Test
    fun `the vertical remainder carries across moves instead of being dropped`() {
        frame(finger(0, 0))
        frame(finger(0, 1000))
        frame(finger(0, 2000))
        assertEquals(listOf(MouseCommand.MoveRel(0, 28)), frame(finger(0, 3000)))
    }

    @Test
    fun `lifting the finger resets the anchor`() {
        frame(finger(0, 0))
        assertTrue(frame(lifted()).isEmpty())
        assertTrue(frame(finger(1000, 1000)).isEmpty())
        assertEquals(listOf(MouseCommand.MoveRel(27, 27)), frame(finger(2000, 2000)))
    }

    @Test
    fun `a new tracking id is a fresh touch even without a lift`() {
        frame(finger(0, 0, trackingId = 1))
        assertTrue(frame(finger(1000, 1000, trackingId = 2)).isEmpty())
    }

    @Test
    fun `leaving the surface releases every held button once`() {
        frame(left = true, middle = true)
        assertEquals(
            listOf(MouseCommand.Button(false, MOUSE_BUTTON_LEFT), MouseCommand.Button(false, MOUSE_BUTTON_MIDDLE)),
            mover.releaseButtons(),
        )
        assertTrue(mover.releaseButtons().isEmpty())
    }

    @Test
    fun `a frame after a release re-sends a still-held button`() {
        frame(left = true)
        mover.releaseButtons()
        assertEquals(listOf(MouseCommand.Button(true, MOUSE_BUTTON_LEFT)), frame(left = true))
    }

    private companion object {
        const val WHEEL_UNITS_PER_NOTCH = 120
    }
}
