// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.overlay

import android.view.MotionEvent
import com.tinkernorth.dish.hotpath.input.PadTouchFrame
import com.tinkernorth.dish.hotpath.input.Pointer

// Every pointer still on the surface after this event: the one lifting on an UP is gone, all of
// them on a CANCEL, and a hover carries no finger at all.
internal fun pointersStillDown(event: MotionEvent): List<Pointer> =
    when (event.actionMasked) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointersExcept(event, event.actionIndex)
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_EXIT,
        -> emptyList()
        else -> pointersExcept(event, NO_LIFTING_INDEX)
    }

// The event's pointers but the one at [liftingIndex], read straight into the one list the frame keeps.
private fun pointersExcept(
    event: MotionEvent,
    liftingIndex: Int,
): List<Pointer> {
    val count = event.pointerCount
    val pointers = ArrayList<Pointer>(count)
    for (index in 0 until count) {
        if (index == liftingIndex) continue
        pointers.add(Pointer(event.getPointerId(index), event.getX(index), event.getY(index)))
    }
    return pointers
}

private const val NO_LIFTING_INDEX = -1

// The slots whose last frame still holds a finger or the click, each with its lifted frame.
internal fun liftedFrames(
    last: Map<String, PadTouchFrame>,
    nowMs: Long,
): Map<String, PadTouchFrame> =
    last
        .filterValues { it.anyFingerDown() || it.buttonPressed }
        .mapValues { (_, frame) -> frame.lifted(nowMs) }

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
