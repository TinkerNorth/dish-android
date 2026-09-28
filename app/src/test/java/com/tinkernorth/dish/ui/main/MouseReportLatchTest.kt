// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

// What the mouse surface last reported, and the resend thread's snapshot of it.
class MouseReportLatchTest {
    private val latch = MouseReportLatch()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun finger(
        x: Int,
        y: Int = 0,
        eventTimeMs: Long = 0L,
    ) = TouchpadSurfaceView.TouchpadState(
        finger0Active = true,
        finger0TrackingId = 1,
        finger0X = x.toShort(),
        finger0Y = y.toShort(),
        eventTimeMs = eventTimeMs,
    )

    @Test
    fun `nothing is reported until the first frame`() {
        assertFalse(latch.hasReported)
        assertFalse(latch.refreshResendSnapshot())
    }

    @Test
    fun `a first frame is a changed snapshot carrying what was reported`() {
        val fingers = finger(FIRST_X, FIRST_Y, eventTimeMs = FRAME_TIME_MS)
        latch.record(fingers, leftHeld = true, rightHeld = false, middleHeld = true)
        assertTrue(latch.hasReported)
        assertTrue(latch.refreshResendSnapshot())
        assertEquals(fingers, latch.resentFingers)
        assertTrue(latch.resentLeftHeld)
        assertFalse(latch.resentRightHeld)
        assertTrue(latch.resentMiddleHeld)
    }

    // An idle first frame equals an untouched snapshot field for field; it is still the first.
    @Test
    fun `a first frame with no finger and no button is still a changed snapshot`() {
        latch.record(TouchpadSurfaceView.TouchpadState(), leftHeld = false, rightHeld = false, middleHeld = false)
        assertTrue(latch.refreshResendSnapshot())
        assertFalse(latch.refreshResendSnapshot())
    }

    @Test
    fun `an unchanged report is not a changed snapshot`() {
        latch.record(finger(FIRST_X), leftHeld = false, rightHeld = false, middleHeld = false)
        latch.refreshResendSnapshot()
        assertFalse(latch.refreshResendSnapshot())
    }

    @Test
    fun `each button alone changes the snapshot and reads back as held`() {
        val fingers = finger(FIRST_X)
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        latch.refreshResendSnapshot()

        latch.record(fingers, leftHeld = true, rightHeld = false, middleHeld = false)
        assertTrue(latch.refreshResendSnapshot())
        assertEquals(listOf(true, false, false), resentButtons())

        latch.record(fingers, leftHeld = false, rightHeld = true, middleHeld = false)
        assertTrue(latch.refreshResendSnapshot())
        assertEquals(listOf(false, true, false), resentButtons())

        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = true)
        assertTrue(latch.refreshResendSnapshot())
        assertEquals(listOf(false, false, true), resentButtons())
    }

    // The surface hands over one state object and mutates it in place, so the snapshot must be
    // a copy: compared by reference it would never change, and it would move under the send.
    @Test
    fun `a finger moving on the same object changes the snapshot`() {
        val fingers = finger(FIRST_X)
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        latch.refreshResendSnapshot()
        fingers.finger0X = SECOND_X.toShort()
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        assertTrue(latch.refreshResendSnapshot())
        assertEquals(SECOND_X.toShort(), latch.resentFingers.finger0X)
    }

    @Test
    fun `the resend snapshot does not follow the live fingers`() {
        val fingers = finger(FIRST_X)
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        latch.refreshResendSnapshot()
        fingers.finger0X = SECOND_X.toShort()
        assertNotSame(fingers, latch.resentFingers)
        assertEquals(FIRST_X.toShort(), latch.resentFingers.finger0X)
    }

    @Test
    fun `a button frame before any touch carries no fingers at the given time`() {
        val frame = latch.latestFingersAt(BUTTON_TIME_MS)
        assertEquals(TouchpadSurfaceView.TouchpadState(eventTimeMs = BUTTON_TIME_MS), frame)
    }

    @Test
    fun `a button frame carries the last fingers at the given time and leaves them untouched`() {
        val fingers = finger(FIRST_X, FIRST_Y, eventTimeMs = FRAME_TIME_MS)
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        val frame = latch.latestFingersAt(BUTTON_TIME_MS)
        assertEquals(finger(FIRST_X, FIRST_Y, eventTimeMs = BUTTON_TIME_MS), frame)
        assertEquals(FRAME_TIME_MS, fingers.eventTimeMs)
    }

    @Test
    fun `a button frame built from a button frame keeps its fingers`() {
        latch.record(finger(FIRST_X, FIRST_Y), leftHeld = false, rightHeld = false, middleHeld = false)
        val first = latch.latestFingersAt(BUTTON_TIME_MS)
        latch.record(first, leftHeld = true, rightHeld = false, middleHeld = false)
        val second = latch.latestFingersAt(BUTTON_TIME_MS + 1)
        assertEquals(finger(FIRST_X, FIRST_Y, eventTimeMs = BUTTON_TIME_MS + 1), second)
    }

    private fun resentButtons() = listOf(latch.resentLeftHeld, latch.resentRightHeld, latch.resentMiddleHeld)

    // One cycle is what a drag with a button tap costs: two touch frames on the surface's
    // object, a button frame built from them, and resend ticks that see a change and none.
    private fun runReportCycle(
        fingers: TouchpadSurfaceView.TouchpadState,
        cycle: Int,
    ) {
        fingers.finger0X = (cycle and CYCLE_X_MASK).toShort()
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        latch.refreshResendSnapshot()
        latch.record(latch.latestFingersAt(cycle.toLong()), leftHeld = true, rightHeld = true, middleHeld = true)
        latch.refreshResendSnapshot()
        latch.refreshResendSnapshot()
        fingers.finger0Y = (cycle and CYCLE_X_MASK).toShort()
        latch.record(fingers, leftHeld = false, rightHeld = false, middleHeld = false)
        latch.refreshResendSnapshot()
    }

    @Test
    fun `a report and a resend tick allocate nothing`() {
        val fingers = finger(FIRST_X)
        repeat(WARMUP_CYCLES) { runReportCycle(fingers, it) }
        val measureOnlyStart = threads.currentThreadAllocatedBytes
        val measureOnlyEnd = threads.currentThreadAllocatedBytes
        val measurementCost = measureOnlyEnd - measureOnlyStart
        val start = threads.currentThreadAllocatedBytes
        for (cycle in 0 until MEASURED_CYCLES) runReportCycle(fingers, cycle)
        val end = threads.currentThreadAllocatedBytes
        val allocatedBytes = end - start - measurementCost
        assertTrue("$allocatedBytes bytes over $MEASURED_CYCLES cycles", allocatedBytes < MEASURED_CYCLES * BYTES_PER_CYCLE_BOUND)
    }

    private companion object {
        const val FIRST_X = 1200
        const val FIRST_Y = -300
        const val SECOND_X = 1300
        const val FRAME_TIME_MS = 40L
        const val BUTTON_TIME_MS = 90L
        const val CYCLE_X_MASK = 0x3FFF
        const val WARMUP_CYCLES = 10

        // Few enough calls that C2 never compiles the latch: its escape analysis would hide an
        // allocation that ART, which has none, still makes.
        const val MEASURED_CYCLES = 1000

        // Half the smallest object: one allocation in any cycle costs 16 bytes or more every
        // cycle, while the JIT's one-off warm-up allocations stay flat as the cycles grow.
        const val BYTES_PER_CYCLE_BOUND = 8
    }
}
