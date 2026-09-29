// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.overlay

import android.view.InputDevice
import android.view.MotionEvent
import com.tinkernorth.dish.hotpath.input.CapturedTouchpadEvent
import com.tinkernorth.dish.hotpath.input.EVERY_POINTER_LIFTING
import com.tinkernorth.dish.hotpath.input.NO_POINTER_LIFTING

// The pointer this event takes off the surface: the one lifting on an UP, all of them on a
// CANCEL, and all of them on a hover, which carries no finger at all.
internal fun liftingIndexOf(
    actionMasked: Int,
    actionIndex: Int,
): Int =
    when (actionMasked) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> actionIndex
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_EXIT,
        -> EVERY_POINTER_LIFTING
        else -> NO_POINTER_LIFTING
    }

// A captured MotionEvent as the mapper reads it, in place: the two fingers the frame keeps are
// read straight into its fields and nothing else is built. One per capture, rebound to each event
// on the main thread.
internal class MotionEventTouchpad : CapturedTouchpadEvent {
    private lateinit var event: MotionEvent
    private lateinit var xRange: InputDevice.MotionRange
    private lateinit var yRange: InputDevice.MotionRange

    fun bind(
        event: MotionEvent,
        xRange: InputDevice.MotionRange,
        yRange: InputDevice.MotionRange,
    ) {
        this.event = event
        this.xRange = xRange
        this.yRange = yRange
    }

    override val pointerCount: Int get() = event.pointerCount

    override fun pointerId(index: Int): Int = event.getPointerId(index)

    override fun x(index: Int): Float = event.getX(index)

    override fun y(index: Int): Float = event.getY(index)

    override val xMin: Float get() = xRange.min
    override val xMax: Float get() = xRange.max
    override val yMin: Float get() = yRange.min
    override val yMax: Float get() = yRange.max
}

// What one tick of the resend loop does with a slot's frame.
internal enum class ResendStep {
    SEND,
    KEEP,
    FORGET,
}

// Send when the pacer says so and a sink is there to take it; forget a slot the app no longer
// routes once its burst is out, since nothing should keep pacing a surface the app stopped
// reading; otherwise keep it for the next tick.
internal fun resendStepFor(
    due: Boolean,
    hasSink: Boolean,
    routed: Boolean,
): ResendStep =
    when {
        due && hasSink -> ResendStep.SEND
        !due && !routed -> ResendStep.FORGET
        else -> ResendStep.KEEP
    }
