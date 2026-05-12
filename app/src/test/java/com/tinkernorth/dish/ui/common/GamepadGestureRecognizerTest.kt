// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.graphics.RectF
import android.view.MotionEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioural tests for [GamepadGestureRecognizer].
 *
 * Pins the key contract from the "d-pad releases when finger leaves the
 * well" bug: once a pointer claims the d-pad on DOWN, every subsequent MOVE
 * for that pointer drives the hat direction from the angle to the d-pad
 * centre — regardless of whether the finger is still inside [GamepadLayout.dpadRect].
 * That mirrors the analog-stick contract verified by [VirtualStickMathTest].
 *
 * The recognizer is `internal`, so this test lives in the same package.
 * Synthetic [MotionEvent]s are constructed with mockk so the test stays free
 * of Android Looper / native dependencies.
 */
class GamepadGestureRecognizerTest {
    private val recognizer = GamepadGestureRecognizer()

    /**
     * Synthetic layout with the d-pad centred at (150, 250) and 100x100 px in
     * size. Every other region is placed far off-screen so unrelated DOWNs
     * don't accidentally claim a pointer.
     *
     * [android.graphics.RectF] is stubbed out by the AGP unit-test classpath
     * (`Method ... not mocked.`), so we mock each instance to return the
     * minimal slice the recognizer touches: [RectF.contains],
     * [RectF.centerX], [RectF.centerY], [RectF.width].
     */
    private val layout =
        GamepadLayout(
            dpadRect = fakeRect(100f, 200f, 200f, 300f),
            abxyRect = fakeRect(FAR, FAR, FAR + 1, FAR + 1),
            lbRect = fakeRect(FAR, FAR, FAR + 1, FAR + 1),
            rbRect = fakeRect(FAR, FAR, FAR + 1, FAR + 1),
            ltRect = fakeRect(FAR, FAR, FAR + 1, FAR + 1),
            rtRect = fakeRect(FAR, FAR, FAR + 1, FAR + 1),
            leftStickCx = FAR,
            leftStickCy = FAR,
            rightStickCx = FAR,
            rightStickCy = FAR,
            l3StickCx = FAR,
            l3StickCy = FAR,
            r3StickCx = FAR,
            r3StickCy = FAR,
            stickRadius = 1f,
            l3StickRadius = 1f,
            btnRadius = 1f,
            smallBtnRadius = 1f,
            selectCx = FAR,
            startCx = FAR,
            homeCx = FAR,
            centerBtnCy = FAR,
        )

    private fun fakeRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): RectF {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val width = right - left
        return mockk {
            every { centerX() } returns cx
            every { centerY() } returns cy
            every { this@mockk.width() } returns width
            val xSlot = slot<Float>()
            val ySlot = slot<Float>()
            every { contains(capture(xSlot), capture(ySlot)) } answers {
                xSlot.captured in left..right && ySlot.captured in top..bottom
            }
        }
    }

    private fun event(
        actionMasked: Int,
        x: Float,
        y: Float,
        pid: Int = POINTER_0,
        actionIndex: Int = 0,
    ): MotionEvent =
        mockk {
            every { this@mockk.actionMasked } returns actionMasked
            every { this@mockk.actionIndex } returns actionIndex
            every { pointerCount } returns 1
            every { getPointerId(0) } returns pid
            every { getX(0) } returns x
            every { getY(0) } returns y
        }

    // ── DOWN: octant resolution ─────────────────────────────────────────────

    @Test
    fun `down east-of-centre inside rect sets HAT_E`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    @Test
    fun `down west-of-centre inside rect sets HAT_W`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 120f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_W, recognizer.state.hatSwitch)
    }

    @Test
    fun `down north-of-centre inside rect sets HAT_N`() {
        // y < centerY means "up" on screen (Android y-down).
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 150f, y = 220f), layout)
        assertEquals(GamepadTouchView.HAT_N, recognizer.state.hatSwitch)
    }

    @Test
    fun `down south-of-centre inside rect sets HAT_S`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 150f, y = 280f), layout)
        assertEquals(GamepadTouchView.HAT_S, recognizer.state.hatSwitch)
    }

    @Test
    fun `down at upper-right inside rect sets HAT_NE`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 220f), layout)
        assertEquals(GamepadTouchView.HAT_NE, recognizer.state.hatSwitch)
    }

    @Test
    fun `down outside dpadRect leaves hat at HAT_NONE`() {
        // (500, 500) is far from the d-pad — no pointer is claimed.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 500f, y = 500f), layout)
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)
    }

    // ── MOVE: drag past the rect keeps the hat registered ──────────────────

    @Test
    fun `drag far east past dpadRect keeps HAT_E registered`() {
        // The regression this test pins: pre-fix, the move handler zeroed
        // hatSwitch to HAT_NONE the moment the finger crossed dpadRect.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    @Test
    fun `drag from east to south while outside rect resolves to HAT_S`() {
        // Mirrors the analog-stick "drag past the well and direction
        // re-resolves" contract — angle is from the d-pad centre, not the
        // last known finger position.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        // Finger now far below the d-pad centre (y >> centerY=250).
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 800f), layout)
        assertEquals(GamepadTouchView.HAT_S, recognizer.state.hatSwitch)
    }

    @Test
    fun `drag back into rect at a different octant updates the hat`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        // Drag well outside to the south, then snap back inside on the
        // north side — hat should follow.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 800f), layout)
        assertEquals(GamepadTouchView.HAT_S, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 220f), layout)
        assertEquals(GamepadTouchView.HAT_N, recognizer.state.hatSwitch)
    }

    @Test
    fun `move for a pointer that didn't claim the dpad does not touch the hat`() {
        // Press east of centre, then send MOVE events for a *different*
        // pointer id — d-pad state must stay locked to the original pointer.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f, pid = POINTER_0), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        // POINTER_1 never claimed the d-pad: a move event for it is ignored
        // (the recognizer only updates the hat for pid == dpadPointerId).
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 800f, pid = POINTER_1), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    // ── UP / CANCEL: clear the hat ──────────────────────────────────────────

    @Test
    fun `up on the dpad pointer clears the hat to HAT_NONE`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)
    }

    @Test
    fun `up after drag outside rect still clears the hat`() {
        // Drag past the rect (which keeps the hat held), then lift — must
        // release cleanly, not stay latched.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)
    }

    @Test
    fun `cancel during drag clears the hat`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 600f, y = 250f), layout)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_CANCEL, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)
    }

    @Test
    fun `reset clears every per-pointer state and the hat`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        recognizer.reset()
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)

        // After reset, a fresh DOWN must be able to claim the d-pad again
        // (dpadPointerId was zeroed back to INVALID_POINTER).
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 120f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_W, recognizer.state.hatSwitch)
    }

    private companion object {
        const val POINTER_0 = 0
        const val POINTER_1 = 1
        const val FAR = 10_000f
    }
}
