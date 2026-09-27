// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.overlay

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.PhysicalReachabilityComposer
import com.tinkernorth.dish.hotpath.input.PadTouchFrame
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Pointer
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.source.connection.TouchpadReport
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadTouchpadCaptureTest {
    private val first = Pointer(3, 1f, 2f)
    private val second = Pointer(5, 3f, 4f)
    private val pointers = listOf(first, second)

    // ---- which pointers are still on the surface after an event ----

    @Test
    fun `on a pointer up only the lifting pointer is gone`() {
        assertEquals(listOf(first), pointersStillDown(MotionEvent.ACTION_POINTER_UP, actionIndex = 1, pointers = pointers))
    }

    @Test
    fun `on an up the lifting pointer is gone`() {
        assertEquals(listOf(second), pointersStillDown(MotionEvent.ACTION_UP, actionIndex = 0, pointers = pointers))
    }

    @Test
    fun `on a cancel every pointer is gone`() {
        assertEquals(emptyList<Pointer>(), pointersStillDown(MotionEvent.ACTION_CANCEL, actionIndex = 0, pointers = pointers))
    }

    @Test
    fun `a hover carries no finger`() {
        val hovers = listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT)
        for (action in hovers) {
            assertEquals("action $action", emptyList<Pointer>(), pointersStillDown(action, actionIndex = 0, pointers = pointers))
        }
    }

    @Test
    fun `a move or a down keeps every pointer`() {
        assertEquals(pointers, pointersStillDown(MotionEvent.ACTION_MOVE, actionIndex = 0, pointers = pointers))
        assertEquals(pointers, pointersStillDown(MotionEvent.ACTION_POINTER_DOWN, actionIndex = 1, pointers = pointers))
    }

    // ---- the lift frames a lost focus or a released capture sends ----

    @Test
    fun `an idle frame is not lifted`() {
        val last = mapOf(SLOT to PadTouchFrame(eventTimeMs = 5L))
        assertTrue(liftedFrames(last, nowMs = 9L).isEmpty())
    }

    @Test
    fun `a frame holding a finger is lifted at the given time`() {
        val last = mapOf(SLOT to PadTouchFrame(finger0Active = true, finger0X = 100, eventTimeMs = 5L))
        val lifted = liftedFrames(last, nowMs = 9L).getValue(SLOT)
        assertFalse(lifted.anyFingerDown())
        assertEquals(9L, lifted.eventTimeMs)
        assertEquals(100.toShort(), lifted.finger0X)
    }

    @Test
    fun `a frame holding only the click is lifted too`() {
        val last = mapOf(SLOT to PadTouchFrame(buttonPressed = true, eventTimeMs = 5L))
        val lifted = liftedFrames(last, nowMs = 9L).getValue(SLOT)
        assertFalse(lifted.buttonPressed)
    }

    // ---- one resend tick's verdict for a slot ----

    @Test
    fun `a due frame with a sink is sent`() {
        assertEquals(ResendStep.SEND, resendStepFor(due = true, hasSink = true, routed = true))
        assertEquals(ResendStep.SEND, resendStepFor(due = true, hasSink = true, routed = false))
    }

    @Test
    fun `a due frame without a sink is kept for the next tick`() {
        assertEquals(ResendStep.KEEP, resendStepFor(due = true, hasSink = false, routed = false))
    }

    @Test
    fun `an unrouted slot is forgotten once its burst is out`() {
        assertEquals(ResendStep.FORGET, resendStepFor(due = false, hasSink = true, routed = false))
    }

    @Test
    fun `a routed slot between bursts is kept`() {
        assertEquals(ResendStep.KEEP, resendStepFor(due = false, hasSink = true, routed = true))
    }

    // ---- the activity's generic-motion hook ----

    private val sink = mockk<TelemetrySink>(relaxed = true)
    private val reachable = MutableStateFlow<Map<String, TelemetrySink>>(emptyMap())
    private val reachability = mockk<PhysicalReachabilityComposer> { every { state } returns reachable }

    private fun capture(): PadTouchpadCapture =
        PadTouchpadCapture(
            rootView = mockk<View>(relaxed = true),
            registry = mockk<PhysicalGamepadRegistry>(),
            reachability = reachability,
            capabilities = mockk<CapabilityComposer>(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun range(
        min: Float,
        max: Float,
    ): InputDevice.MotionRange =
        mockk {
            every { this@mockk.min } returns min
            every { this@mockk.max } returns max
        }

    private fun surface(withRanges: Boolean): InputDevice =
        mockk {
            every { getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD) } returns
                if (withRanges) range(0f, DS4_X_MAX) else null
            every { getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD) } returns
                if (withRanges) range(0f, DS4_Y_MAX) else null
        }

    private fun capturedMove(
        deviceId: Int,
        surface: InputDevice,
    ): MotionEvent =
        mockk {
            every { source } returns InputDevice.SOURCE_TOUCHPAD
            every { this@mockk.deviceId } returns deviceId
            every { device } returns surface
            every { actionMasked } returns MotionEvent.ACTION_MOVE
            every { actionIndex } returns 0
            every { pointerCount } returns 1
            every { getPointerId(0) } returns POINTER_ID
            every { getX(0) } returns DS4_X_MAX
            every { getY(0) } returns 0f
            every { buttonState } returns MotionEvent.BUTTON_PRIMARY
            every { eventTime } returns EVENT_TIME_MS
        }

    @Test
    fun `an event from a surface the app does not route is left alone`() {
        val capture = capture()
        capture.installRoutes(mapOf(SURFACE to SLOT))
        assertFalse(capture.onGenericMotionEvent(capturedMove(OTHER_SURFACE, surface(withRanges = true))))
        verify { sink wasNot Called }
    }

    @Test
    fun `a captured event with no axis range is consumed but never sent`() {
        val capture = capture()
        capture.installRoutes(mapOf(SURFACE to SLOT))
        reachable.value = mapOf(SLOT to sink)

        assertTrue(capture.onGenericMotionEvent(capturedMove(SURFACE, surface(withRanges = false))))
        assertTrue(capture.onGenericMotionEvent(capturedMove(SURFACE, surface(withRanges = false))))

        verify { sink wasNot Called }
    }

    @Test
    fun `a captured frame is sent to the slot's sink in the wire's shape`() {
        val capture = capture()
        capture.installRoutes(mapOf(SURFACE to SLOT))
        reachable.value = mapOf(SLOT to sink)

        assertTrue(capture.onGenericMotionEvent(capturedMove(SURFACE, surface(withRanges = true))))

        verify(exactly = 1) { sink.sendTouchpad(SLOT, match(::isTheFarRightClickedFrame)) }
    }

    private fun isTheFarRightClickedFrame(report: TouchpadReport): Boolean {
        val oneFingerFarRight = report.finger0Active && !report.finger1Active && report.finger0X == Short.MAX_VALUE
        val stamped = report.finger0TrackingId == POINTER_ID && report.eventTimeMs == EVENT_TIME_MS
        return oneFingerFarRight && report.buttonPressed && stamped
    }

    @Test
    fun `a captured frame for a slot with no sink is consumed and held back`() {
        val capture = capture()
        capture.installRoutes(mapOf(SURFACE to SLOT))

        assertTrue(capture.onGenericMotionEvent(capturedMove(SURFACE, surface(withRanges = true))))

        verify { sink wasNot Called }
    }

    private companion object {
        const val SLOT = "7"
        const val SURFACE = 31
        const val OTHER_SURFACE = 32
        const val POINTER_ID = 3
        const val EVENT_TIME_MS = 42L
        const val DS4_X_MAX = 1919f
        const val DS4_Y_MAX = 941f
    }
}
