// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_LEFT
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_MIDDLE
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_RIGHT
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView

private const val WHEEL_DELTA_PER_NOTCH = 120

// One full sweep of the move surface (65535 normalized units) travels this many host pixels;
// the float remainders keep slow drags from rounding to nothing.
private const val MOONLIGHT_MOVE_PX_PER_SWEEP = 1800f
private const val NORM_INT16_SPAN = 65535f
private const val MOONLIGHT_MOVE_SCALE = MOONLIGHT_MOVE_PX_PER_SWEEP / NORM_INT16_SPAN

// A scroll strip notch in Windows wheel units, clamped to the int16 the wire carries.
internal fun wheelDeltaFor(scrollNotches: Int): Int =
    (scrollNotches * WHEEL_DELTA_PER_NOTCH).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

// One packet for the Moonlight control stream, in the order the host must see them.
internal sealed interface MouseCommand {
    data class Button(
        val down: Boolean,
        val button: Int,
    ) : MouseCommand

    data class Scroll(
        val amount: Int,
    ) : MouseCommand

    data class MoveRel(
        val dx: Int,
        val dy: Int,
    ) : MouseCommand
}

// Turns the mouse surface's frames into Moonlight's edge-triggered packets: a button is sent
// only when its held state changes, scroll only when there are notches, and a finger becomes
// relative moves against the point it landed on, so a fresh touch anchors instead of jumping
// the cursor across the pad. Whole pixels go out; the fraction carries to the next frame.
internal class MoonlightMouseMover {
    private var leftSent = false
    private var rightSent = false
    private var middleSent = false
    private var trackingId = Int.MIN_VALUE
    private var lastX = 0
    private var lastY = 0
    private var remainderX = 0f
    private var remainderY = 0f

    fun onFrame(
        fingers: TouchpadSurfaceView.TouchpadState,
        scrollNotches: Int,
        leftHeld: Boolean,
        rightHeld: Boolean,
        middleHeld: Boolean,
    ): List<MouseCommand> {
        val commands = mutableListOf<MouseCommand>()
        if (leftHeld != leftSent) {
            commands.add(MouseCommand.Button(leftHeld, MOUSE_BUTTON_LEFT))
            leftSent = leftHeld
        }
        if (rightHeld != rightSent) {
            commands.add(MouseCommand.Button(rightHeld, MOUSE_BUTTON_RIGHT))
            rightSent = rightHeld
        }
        if (middleHeld != middleSent) {
            commands.add(MouseCommand.Button(middleHeld, MOUSE_BUTTON_MIDDLE))
            middleSent = middleHeld
        }
        if (scrollNotches != 0) commands.add(MouseCommand.Scroll(wheelDeltaFor(scrollNotches)))
        moveFor(fingers)?.let { commands.add(it) }
        return commands
    }

    // Leaving the surface must never strand a held button on the host.
    fun releaseButtons(): List<MouseCommand> {
        val commands = mutableListOf<MouseCommand>()
        if (leftSent) {
            commands.add(MouseCommand.Button(false, MOUSE_BUTTON_LEFT))
            leftSent = false
        }
        if (rightSent) {
            commands.add(MouseCommand.Button(false, MOUSE_BUTTON_RIGHT))
            rightSent = false
        }
        if (middleSent) {
            commands.add(MouseCommand.Button(false, MOUSE_BUTTON_MIDDLE))
            middleSent = false
        }
        return commands
    }

    private fun moveFor(fingers: TouchpadSurfaceView.TouchpadState): MouseCommand.MoveRel? {
        if (!fingers.finger0Active) {
            trackingId = Int.MIN_VALUE
            return null
        }
        val x = fingers.finger0X.toInt()
        val y = fingers.finger0Y.toInt()
        val freshTouch = fingers.finger0TrackingId != trackingId
        if (freshTouch) {
            anchorAt(fingers.finger0TrackingId, x, y)
            return null
        }
        remainderX += (x - lastX) * MOONLIGHT_MOVE_SCALE
        remainderY += (y - lastY) * MOONLIGHT_MOVE_SCALE
        lastX = x
        lastY = y
        val dx = remainderX.toInt()
        val dy = remainderY.toInt()
        if (dx == 0 && dy == 0) return null
        remainderX -= dx
        remainderY -= dy
        return MouseCommand.MoveRel(dx, dy)
    }

    private fun anchorAt(
        id: Int,
        x: Int,
        y: Int,
    ) {
        trackingId = id
        lastX = x
        lastY = y
    }
}
