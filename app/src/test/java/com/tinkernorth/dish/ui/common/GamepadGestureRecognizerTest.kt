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
            // ABXY centred at (1000, 1000) with btnRadius = 10 → A at (1000,1015),
            // B at (1015,1000), X at (985,1000), Y at (1000,985). Pickup radius
            // is 1.3·10 = 13; spacing between adjacent button centres is
            // 1.5·10·√2 ≈ 21.2, so the midpoint between A and B (1007.5, 1007.5)
            // lands inside both pickup discs (10.6 < 13) and the multi-button
            // tests below rely on those exact distances.
            abxyRect = fakeRect(985f, 985f, 1015f, 1015f),
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
            btnRadius = 10f,
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

    // ── D-pad diagonal sweet-spot (ratio threshold) ────────────────────────

    @Test
    fun `down east with small north offset above threshold sets HAT_NE`() {
        // dx = 30, dy = -10 → minor/major = 0.33 ≥ DPAD_DIAGONAL_THRESHOLD (0.3).
        // The old 22.5° geometric-octant logic would have snapped this to HAT_E
        // because the angle is only ~18° from the east axis.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 240f), layout)
        assertEquals(GamepadTouchView.HAT_NE, recognizer.state.hatSwitch)
    }

    @Test
    fun `down east with tiny north offset below threshold stays cardinal HAT_E`() {
        // dx = 30, dy = -2 → minor/major ≈ 0.067 < 0.3, so the finger sits well
        // inside the cardinal-east band and we don't accidentally flip to NE.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 248f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    @Test
    fun `down upper-left with dominant west still resolves to HAT_NW`() {
        // dx = -30, dy = -12 → minor/major = 0.4 ≥ 0.3 → diagonal NW, even
        // though the touch is much closer to "pure left" than to a 45° angle.
        // This is the regression the user reported: pre-fix, "up + left"
        // almost always collapsed to one or the other.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 120f, y = 238f), layout)
        assertEquals(GamepadTouchView.HAT_NW, recognizer.state.hatSwitch)
    }

    // ── ABXY: live-tracking zone resolver ──────────────────────────────────

    @Test
    fun `down on B button sets BTN_B`() {
        // (1015, 1000) is 15 east of the cluster centre — well past the
        // ABXY_CENTER_ZONE_FRACTION centre disc and inside the east sector,
        // which resolves to a single B bit.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        assertEquals(GamepadTouchView.BTN_B, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `down at cluster centre triggers all four buttons`() {
        // The new "centre = all four" sweet-spot the user explicitly asked
        // for. With ABXY_CENTER_ZONE_FRACTION = 0.5 and btnRadius = 10 the
        // centre disc has radius 7.5; (1000, 1000) is dead centre.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1000f, y = 1000f), layout)
        val all =
            GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
                GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y
        assertEquals(all, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `down between A and B in SE sector sets both bits`() {
        // (1007.5, 1007.5) is 10.6 from centre — outside the 7.5-unit
        // centre disc — and at a perfect 45° angle, putting it in the SE
        // diagonal sector which maps to A + B.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1007.5f, y = 1007.5f), layout)
        val expected = GamepadTouchView.BTN_A or GamepadTouchView.BTN_B
        assertEquals(expected, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `drag from B in same direction past cluster keeps BTN_B`() {
        // Zones extend infinitely past the visible cluster, so a drag in the
        // same east direction stays inside the east sector — bits unchanged.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 2000f, y = 1000f), layout)
        assertEquals(GamepadTouchView.BTN_B, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `drag from B into A sector replaces BTN_B with BTN_A`() {
        // The "turn off as I change direction" fix: live tracking means the
        // held bits follow the finger across zones — sliding from B (east)
        // to A (south) drops B and picks up A, instead of accumulating.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 1000f, y = 1015f), layout)
        assertEquals(GamepadTouchView.BTN_A, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `drag from B into centre zone triggers all four`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 1000f, y = 1000f), layout)
        val all =
            GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
                GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y
        assertEquals(all, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `up after drag releases all bits for that pointer`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 1000f, y = 1015f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 1000f, y = 1015f), layout)
        assertEquals(0, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `two pointers each hold a different button concurrently`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f, pid = POINTER_0), layout)
        recognizer.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_DOWN, x = 1000f, y = 1015f, pid = POINTER_1),
            layout,
        )
        val expected = GamepadTouchView.BTN_A or GamepadTouchView.BTN_B
        assertEquals(expected, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `releasing one pointer doesn't release the other's button`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f, pid = POINTER_0), layout)
        recognizer.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_DOWN, x = 1000f, y = 1015f, pid = POINTER_1),
            layout,
        )
        recognizer.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_UP, x = 1015f, y = 1000f, pid = POINTER_0),
            layout,
        )
        assertEquals(GamepadTouchView.BTN_A, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `two pointers holding the same button - releasing one keeps it latched`() {
        // Both fingers on B. Lifting the first must leave B held by the second
        // — the aggregation OR'd both pointers' east-zone bits, so removing
        // one still leaves the other's contribution.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f, pid = POINTER_0), layout)
        recognizer.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_DOWN, x = 1015f, y = 1000f, pid = POINTER_1),
            layout,
        )
        assertEquals(GamepadTouchView.BTN_B, recognizer.state.buttons and ABXY_MASK)

        recognizer.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_UP, x = 1015f, y = 1000f, pid = POINTER_0),
            layout,
        )
        assertEquals(GamepadTouchView.BTN_B, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `cancel during ABXY hold clears every active bit`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1007.5f, y = 1007.5f), layout)
        val both = GamepadTouchView.BTN_A or GamepadTouchView.BTN_B
        assertEquals(both, recognizer.state.buttons and ABXY_MASK)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_CANCEL, x = 1007.5f, y = 1007.5f), layout)
        assertEquals(0, recognizer.state.buttons and ABXY_MASK)
    }

    private companion object {
        const val POINTER_0 = 0
        const val POINTER_1 = 1
        const val FAR = 10_000f

        val ABXY_MASK: Int =
            GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
                GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y
    }
}
