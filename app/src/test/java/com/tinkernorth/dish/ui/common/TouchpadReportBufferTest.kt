// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.source.connection.TouchpadReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

// The wire frame an overlay sends for its surface's state, refilled in the one report the sending
// thread owns.
class TouchpadReportBufferTest {
    private val buffer = TouchpadReportBuffer()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun twoFingers() =
        TouchpadSurfaceView.TouchpadState(
            finger0Active = true,
            finger1Active = true,
            buttonPressed = false,
            finger0TrackingId = FINGER0_ID,
            finger0X = FINGER0_X,
            finger0Y = FINGER0_Y,
            finger1TrackingId = FINGER1_ID,
            finger1X = FINGER1_X,
            finger1Y = FINGER1_Y,
            eventTimeMs = EVENT_TIME_MS,
        )

    @Test
    fun `a report carries every finger field and the caller's buttons and wheel`() {
        val report = buffer.reportOf(twoFingers(), buttonPressed = true, rightPressed = true, middlePressed = true, scrollDelta = SCROLL)
        val expected =
            TouchpadReport(
                finger0Active = true,
                finger1Active = true,
                buttonPressed = true,
                rightPressed = true,
                middlePressed = true,
                finger0TrackingId = FINGER0_ID,
                finger0X = FINGER0_X,
                finger0Y = FINGER0_Y,
                finger1TrackingId = FINGER1_ID,
                finger1X = FINGER1_X,
                finger1Y = FINGER1_Y,
                eventTimeMs = EVENT_TIME_MS,
                scrollDelta = SCROLL,
            )
        assertEquals(expected, report)
    }

    // The click is the caller's, not the surface's: a pad surface sends its own click, the mouse
    // surface its left button.
    @Test
    fun `the click is the caller's and not the surface's`() {
        val fingers = twoFingers()
        fingers.buttonPressed = true
        assertFalse(buffer.reportOf(fingers, buttonPressed = false).buttonPressed)
    }

    @Test
    fun `a pad surface's report has no mouse buttons and no wheel`() {
        val report = buffer.reportOf(twoFingers(), buttonPressed = true)
        assertFalse(report.rightPressed)
        assertFalse(report.middlePressed)
        assertEquals(0.toShort(), report.scrollDelta)
    }

    @Test
    fun `every report is the same one refilled`() {
        val first = buffer.reportOf(twoFingers(), buttonPressed = true, rightPressed = true, middlePressed = true, scrollDelta = SCROLL)
        val second = buffer.reportOf(TouchpadSurfaceView.TouchpadState(), buttonPressed = false)
        assertSame(first, second)
        assertEquals(TouchpadSurfaceView.TouchpadState().toWireFrame(), second)
    }

    // One report per sending thread: the UI thread's frame and the resend thread's never share one.
    @Test
    fun `two buffers hold two reports`() {
        val other = TouchpadReportBuffer()
        assertNotSame(buffer.reportOf(twoFingers(), true), other.reportOf(twoFingers(), true))
    }

    private fun runReportCycle(
        fingers: TouchpadSurfaceView.TouchpadState,
        lifted: TouchpadSurfaceView.TouchpadState,
    ): Int {
        val padTrackingId = buffer.reportOf(fingers, buttonPressed = true).finger0TrackingId
        val mouse = buffer.reportOf(lifted, buttonPressed = false, rightPressed = true, middlePressed = false, scrollDelta = SCROLL)
        val mouseScroll = mouse.scrollDelta
        return padTrackingId + mouseScroll
    }

    @Test
    fun `a report allocates nothing`() {
        val fingers = twoFingers()
        val lifted = TouchpadSurfaceView.TouchpadState()
        repeat(WARMUP_CYCLES) { runReportCycle(fingers, lifted) }
        val measureOnlyStart = threads.currentThreadAllocatedBytes
        val measureOnlyEnd = threads.currentThreadAllocatedBytes
        val measurementCost = measureOnlyEnd - measureOnlyStart
        var checksum = 0
        val start = threads.currentThreadAllocatedBytes
        repeat(MEASURED_CYCLES) { checksum += runReportCycle(fingers, lifted) }
        val end = threads.currentThreadAllocatedBytes
        val allocatedBytes = end - start - measurementCost
        assertEquals(MEASURED_CYCLES * (FINGER0_ID + SCROLL), checksum)
        assertTrue("$allocatedBytes bytes over $MEASURED_CYCLES cycles", allocatedBytes < MEASURED_CYCLES * BYTES_PER_CYCLE_BOUND)
    }

    private fun TouchpadSurfaceView.TouchpadState.toWireFrame() =
        TouchpadReport(
            finger0Active = finger0Active,
            finger1Active = finger1Active,
            buttonPressed = false,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = finger0TrackingId,
            finger0X = finger0X,
            finger0Y = finger0Y,
            finger1TrackingId = finger1TrackingId,
            finger1X = finger1X,
            finger1Y = finger1Y,
            eventTimeMs = eventTimeMs,
            scrollDelta = 0,
        )

    private companion object {
        const val FINGER0_ID = 7
        const val FINGER0_X: Short = -1200
        const val FINGER0_Y: Short = 3400
        const val FINGER1_ID = 8
        const val FINGER1_X: Short = 5600
        const val FINGER1_Y: Short = -7800
        const val EVENT_TIME_MS = 123_456L
        const val SCROLL: Short = -240
        const val WARMUP_CYCLES = 10

        // Few enough calls that C2 never compiles the buffer: its escape analysis would hide an
        // allocation that ART, which has none, still makes.
        const val MEASURED_CYCLES = 1000

        // Half the smallest object: one allocation in any cycle costs 16 bytes or more every
        // cycle, while the JIT's one-off warm-up allocations stay flat as the cycles grow.
        const val BYTES_PER_CYCLE_BOUND = 8
    }
}
