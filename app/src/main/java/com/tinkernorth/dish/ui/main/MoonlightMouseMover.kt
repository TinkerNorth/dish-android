// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_LEFT
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_MIDDLE
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_RIGHT
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
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

// Where the mover's packets go, called in the order the host must see them. The overlay
// implements it once over its Moonlight connection, so a touch frame builds nothing.
internal interface MoonlightMouseSink {
    fun sendMouseButton(
        down: Boolean,
        button: Int,
    )

    fun sendMouseScroll(amount: Int)

    fun sendMouseMoveRel(
        dx: Int,
        dy: Int,
    )
}

// The sink over a live Moonlight connection.
internal class MoonlightConnectionMouseSink(
    val connection: MoonlightConnection,
) : MoonlightMouseSink {
    override fun sendMouseButton(
        down: Boolean,
        button: Int,
    ) = connection.sendMouseButton(down, button)

    override fun sendMouseScroll(amount: Int) = connection.sendMouseScroll(amount)

    override fun sendMouseMoveRel(
        dx: Int,
        dy: Int,
    ) = connection.sendMouseMoveRel(dx, dy)
}

// The sink a frame sends through: the one already held while the connection object is the
// same, a new one once a reconnect has replaced it.
internal fun moonlightMouseSinkFor(
    held: MoonlightConnectionMouseSink?,
    connection: MoonlightConnection,
): MoonlightConnectionMouseSink {
    val heldIsForThisConnection = held != null && held.connection === connection
    if (heldIsForThisConnection) return held
    return MoonlightConnectionMouseSink(connection)
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
        sink: MoonlightMouseSink,
        fingers: TouchpadSurfaceView.TouchpadState,
        scrollNotches: Int,
        leftHeld: Boolean,
        rightHeld: Boolean,
        middleHeld: Boolean,
    ) {
        leftSent = sendButtonEdge(sink, MOUSE_BUTTON_LEFT, held = leftHeld, sent = leftSent)
        rightSent = sendButtonEdge(sink, MOUSE_BUTTON_RIGHT, held = rightHeld, sent = rightSent)
        middleSent = sendButtonEdge(sink, MOUSE_BUTTON_MIDDLE, held = middleHeld, sent = middleSent)
        if (scrollNotches != 0) sink.sendMouseScroll(wheelDeltaFor(scrollNotches))
        sendMove(sink, fingers)
    }

    // Leaving the surface must never strand a held button on the host.
    fun releaseButtons(sink: MoonlightMouseSink) {
        leftSent = sendButtonEdge(sink, MOUSE_BUTTON_LEFT, held = false, sent = leftSent)
        rightSent = sendButtonEdge(sink, MOUSE_BUTTON_RIGHT, held = false, sent = rightSent)
        middleSent = sendButtonEdge(sink, MOUSE_BUTTON_MIDDLE, held = false, sent = middleSent)
    }

    // Returns what the host now holds for this button.
    private fun sendButtonEdge(
        sink: MoonlightMouseSink,
        button: Int,
        held: Boolean,
        sent: Boolean,
    ): Boolean {
        if (held != sent) sink.sendMouseButton(held, button)
        return held
    }

    private fun sendMove(
        sink: MoonlightMouseSink,
        fingers: TouchpadSurfaceView.TouchpadState,
    ) {
        if (!fingers.finger0Active) {
            trackingId = Int.MIN_VALUE
            return
        }
        val x = fingers.finger0X.toInt()
        val y = fingers.finger0Y.toInt()
        val freshTouch = fingers.finger0TrackingId != trackingId
        if (freshTouch) {
            anchorAt(fingers.finger0TrackingId, x, y)
            return
        }
        remainderX += (x - lastX) * MOONLIGHT_MOVE_SCALE
        remainderY += (y - lastY) * MOONLIGHT_MOVE_SCALE
        lastX = x
        lastY = y
        val dx = remainderX.toInt()
        val dy = remainderY.toInt()
        if (dx == 0 && dy == 0) return
        remainderX -= dx
        remainderY -= dy
        sink.sendMouseMoveRel(dx, dy)
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
