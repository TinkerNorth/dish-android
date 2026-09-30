// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.hotpath.input.PadTouchFrame
import com.tinkernorth.dish.source.connection.TouchpadReport

/**
 * The one wire frame a sending thread refills for every touch frame it sends, so a frame builds
 * nothing. One per thread: an overlay's UI thread and its resend thread each hold their own, as do
 * a captured pad surface's main and resend threads and the Moonlight bridge's dispatch thread.
 */
class TouchpadReportBuffer {
    private val report =
        TouchpadReport(
            finger0Active = false,
            finger1Active = false,
            buttonPressed = false,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = 0,
            finger0X = 0,
            finger0Y = 0,
            finger1TrackingId = 0,
            finger1X = 0,
            finger1Y = 0,
            eventTimeMs = 0L,
            scrollDelta = 0,
        )

    /**
     * The frame for [fingers], in this buffer's one report. The click and the mouse-mode fields
     * are the caller's: a pad surface sends its own click, the mouse surface its buttons and wheel.
     */
    fun reportOf(
        fingers: TouchpadSurfaceView.TouchpadState,
        buttonPressed: Boolean,
        rightPressed: Boolean = false,
        middlePressed: Boolean = false,
        scrollDelta: Short = 0,
    ): TouchpadReport {
        report.finger0Active = fingers.finger0Active
        report.finger1Active = fingers.finger1Active
        report.buttonPressed = buttonPressed
        report.rightPressed = rightPressed
        report.middlePressed = middlePressed
        report.finger0TrackingId = fingers.finger0TrackingId
        report.finger0X = fingers.finger0X
        report.finger0Y = fingers.finger0Y
        report.finger1TrackingId = fingers.finger1TrackingId
        report.finger1X = fingers.finger1X
        report.finger1Y = fingers.finger1Y
        report.eventTimeMs = fingers.eventTimeMs
        report.scrollDelta = scrollDelta
        return report
    }

    /** The frame a pad's own captured surface produced: its fingers, its click and its time. */
    fun reportOf(frame: PadTouchFrame): TouchpadReport {
        report.finger0Active = frame.finger0Active
        report.finger1Active = frame.finger1Active
        report.buttonPressed = frame.buttonPressed
        report.rightPressed = false
        report.middlePressed = false
        report.finger0TrackingId = frame.finger0Id
        report.finger0X = frame.finger0X
        report.finger0Y = frame.finger0Y
        report.finger1TrackingId = frame.finger1Id
        report.finger1X = frame.finger1X
        report.finger1Y = frame.finger1Y
        report.eventTimeMs = frame.eventTimeMs
        report.scrollDelta = 0
        return report
    }

    /** A USB-direct pad's frame as the native decoder hands it over, which stamps no sample time. */
    fun reportOf(
        finger0Active: Boolean,
        finger0Id: Int,
        finger0X: Short,
        finger0Y: Short,
        finger1Active: Boolean,
        finger1Id: Int,
        finger1X: Short,
        finger1Y: Short,
        buttonPressed: Boolean,
    ): TouchpadReport {
        report.finger0Active = finger0Active
        report.finger1Active = finger1Active
        report.buttonPressed = buttonPressed
        report.rightPressed = false
        report.middlePressed = false
        report.finger0TrackingId = finger0Id
        report.finger0X = finger0X
        report.finger0Y = finger0Y
        report.finger1TrackingId = finger1Id
        report.finger1X = finger1X
        report.finger1Y = finger1Y
        report.eventTimeMs = 0L
        report.scrollDelta = 0
        return report
    }
}
