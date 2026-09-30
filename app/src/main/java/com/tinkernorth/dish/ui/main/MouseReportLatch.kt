// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.ui.common.TouchpadSurfaceView

private const val LEFT_HELD = 1
private const val RIGHT_HELD = 2
private const val MIDDLE_HELD = 4

private fun heldBits(
    leftHeld: Boolean,
    rightHeld: Boolean,
    middleHeld: Boolean,
): Int {
    var bits = 0
    if (leftHeld) bits = bits or LEFT_HELD
    if (rightHeld) bits = bits or RIGHT_HELD
    if (middleHeld) bits = bits or MIDDLE_HELD
    return bits
}

// What the mouse surface last reported, written by the UI thread on every frame, and the resend
// thread's snapshot of it, taken on every tick. Neither side allocates.
//
// The held buttons pack into one volatile Int, so the resend thread always reads a set the UI
// thread reported together. The fingers are the surface's own state object, which the UI thread
// mutates in place (or the latch's button frame, likewise): the resend thread copies them into
// its snapshot, and a copy torn by a frame landing mid-copy differs from the next tick's, which
// then resends the settled state. A resend can trail a fresher frame the UI thread sent
// meanwhile by one tick; that was so when each frame published an immutable holder too.
internal class MouseReportLatch {
    @Volatile private var reportedFingers: TouchpadSurfaceView.TouchpadState? = null

    @Volatile private var reportedButtons = 0

    // UI-thread only: the frame a button or scroll event sends, rebuilt from the last fingers.
    private val buttonFrame = TouchpadSurfaceView.TouchpadState()
    private val noFingers = TouchpadSurfaceView.TouchpadState()

    // Resend-thread only.
    private val snapshotFingers = TouchpadSurfaceView.TouchpadState()
    private var snapshotButtons = 0
    private var snapshotTaken = false

    val hasReported: Boolean get() = reportedFingers != null

    // The snapshot the last refreshResendSnapshot took; read on the resend thread only.
    val resentFingers: TouchpadSurfaceView.TouchpadState get() = snapshotFingers
    val resentLeftHeld: Boolean get() = snapshotButtons and LEFT_HELD != 0
    val resentRightHeld: Boolean get() = snapshotButtons and RIGHT_HELD != 0
    val resentMiddleHeld: Boolean get() = snapshotButtons and MIDDLE_HELD != 0

    // UI thread: [fingers] is kept, not copied, so the surface's object is read live.
    fun record(
        fingers: TouchpadSurfaceView.TouchpadState,
        leftHeld: Boolean,
        rightHeld: Boolean,
        middleHeld: Boolean,
    ) {
        reportedButtons = heldBits(leftHeld, rightHeld, middleHeld)
        reportedFingers = fingers
    }

    // UI thread: the last fingers (none before the first frame) stamped [nowMs], in the latch's
    // one button frame. The reported object itself is left as it was.
    fun latestFingersAt(nowMs: Long): TouchpadSurfaceView.TouchpadState {
        buttonFrame.copyFrom(reportedFingers ?: noFingers)
        buttonFrame.eventTimeMs = nowMs
        return buttonFrame
    }

    // Resend thread: copies the latest report into the snapshot and answers whether it differs
    // from the one before. False before the first report.
    fun refreshResendSnapshot(): Boolean {
        val fingers = reportedFingers ?: return false
        val buttons = reportedButtons
        val changed = !snapshotTaken || buttons != snapshotButtons || fingers != snapshotFingers
        if (!changed) return false
        snapshotFingers.copyFrom(fingers)
        snapshotButtons = buttons
        snapshotTaken = true
        return true
    }
}
