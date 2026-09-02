// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.graphics.RectF
import android.view.MotionEvent
import com.tinkernorth.dish.core.input.hidToXusb
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadGestureRecognizerTest {
    private val recognizer = GamepadGestureRecognizer()

    private val layout =
        GamepadLayout(
            dpadRect = fakeRect(100f, 200f, 200f, 300f),
            // ABXY centred at (1000,1000), btnRadius=10 → A(1000,1015) B(1015,1000)
            // X(985,1000) Y(1000,985); pickup radius 13; midpoint A↔B at (1007.5,1007.5)
            // → distance 10.6, inside pickup radius.
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
            homeCy = FAR,
            centerBtnCy = FAR,
        )

    // Trackpad zone spanning x 400..600, y 0..100: centre maps to a (0,0) wire frame.
    private val trackpadLayout = layout.copy(trackpadRect = fakeRect(400f, 0f, 600f, 100f))

    @Test
    fun `trigger rail value ramps from the bottom and pins full in the top zone`() {
        // Rail spans y 100..500; full zone = top 25% (100..200); ramp = 200..500.
        assertEquals(255, triggerRailValue(y = 150f, top = 100f, bottom = 500f, analog = true))
        assertEquals(255, triggerRailValue(y = 200f, top = 100f, bottom = 500f, analog = true))
        // Halfway down the ramp reads half pull.
        assertEquals(127, triggerRailValue(y = 350f, top = 100f, bottom = 500f, analog = true))
        assertEquals(0, triggerRailValue(y = 500f, top = 100f, bottom = 500f, analog = true))
        // Clamped past either edge.
        assertEquals(255, triggerRailValue(y = 0f, top = 100f, bottom = 500f, analog = true))
        assertEquals(0, triggerRailValue(y = 900f, top = 100f, bottom = 500f, analog = true))
    }

    @Test
    fun `digital trigger types always read a full press`() {
        assertEquals(255, triggerRailValue(y = 499f, top = 100f, bottom = 500f, analog = false))
    }

    @Test
    fun `a trigger rail gesture slides the value and releases to zero`() {
        val rail = fakeRect(0f, 100f, 52f, 500f)
        val railLayout = layout.copy(ltRect = rail)
        // Touch at half the ramp: a partial pull, not a full press.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, 26f, 350f), railLayout)
        assertEquals(127, recognizer.state.leftTrigger)
        // Slide up into the full zone: whole-button press.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, 26f, 150f), railLayout)
        assertEquals(255, recognizer.state.leftTrigger)
        // Slide back down: value follows the finger.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, 26f, 425f), railLayout)
        assertEquals(63, recognizer.state.leftTrigger)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, 26f, 425f), railLayout)
        assertEquals(0, recognizer.state.leftTrigger)
    }

    @Test
    fun `a digital-trigger type presses full from anywhere on the rail`() {
        val rail = fakeRect(0f, 100f, 52f, 500f)
        val railLayout = layout.copy(ltRect = rail)
        recognizer.analogTriggers = false
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, 26f, 480f), railLayout)
        assertEquals(255, recognizer.state.leftTrigger)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, 26f, 480f), railLayout)
        assertEquals(0, recognizer.state.leftTrigger)
    }

    private fun fakeRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): RectF {
        // RectF is stubbed out by AGP unit-test classpath; mock the slice the recognizer touches.
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val width = right - left
        val height = bottom - top
        return mockk<RectF> {
            // Real fields on the stub class carry the geometry the analog
            // trigger rail reads directly.
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
            every { centerX() } returns cx
            every { centerY() } returns cy
            every { this@mockk.width() } returns width
            every { this@mockk.height() } returns height
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
            every { historySize } returns 0
        }

    private fun moveEventWithHistory(
        history: List<Pair<Float, Float>>,
        currentX: Float,
        currentY: Float,
        pid: Int = POINTER_0,
    ): MotionEvent =
        mockk {
            every { this@mockk.actionMasked } returns MotionEvent.ACTION_MOVE
            every { this@mockk.actionIndex } returns 0
            every { pointerCount } returns 1
            every { getPointerId(0) } returns pid
            every { getX(0) } returns currentX
            every { getY(0) } returns currentY
            every { historySize } returns history.size
            history.forEachIndexed { h, (hx, hy) ->
                every { getHistoricalX(0, h) } returns hx
                every { getHistoricalY(0, h) } returns hy
            }
        }

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
        // y < centerY = up (Android y-down).
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
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 500f, y = 500f), layout)
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)
    }

    @Test
    fun `drag far east past dpadRect keeps HAT_E registered`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    @Test
    fun `drag from east to south while outside rect resolves to HAT_S`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 800f), layout)
        assertEquals(GamepadTouchView.HAT_S, recognizer.state.hatSwitch)
    }

    @Test
    fun `drag back into rect at a different octant updates the hat`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 800f), layout)
        assertEquals(GamepadTouchView.HAT_S, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 220f), layout)
        assertEquals(GamepadTouchView.HAT_N, recognizer.state.hatSwitch)
    }

    @Test
    fun `move for a pointer that didn't claim the dpad does not touch the hat`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f, pid = POINTER_0), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 800f, pid = POINTER_1), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    @Test
    fun `up on the dpad pointer clears the hat to HAT_NONE`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 600f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_NONE, recognizer.state.hatSwitch)
    }

    @Test
    fun `up after drag outside rect still clears the hat`() {
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

        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 120f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_W, recognizer.state.hatSwitch)
    }

    @Test
    fun `down east with small north offset above threshold sets HAT_NE`() {
        // dx=30, dy=-10 → ratio 0.33 ≥ DPAD_DIAGONAL_THRESHOLD (0.3).
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 240f), layout)
        assertEquals(GamepadTouchView.HAT_NE, recognizer.state.hatSwitch)
    }

    @Test
    fun `down east with tiny north offset below threshold stays cardinal HAT_E`() {
        // dx=30, dy=-2 → ratio ≈ 0.067 < 0.3.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 248f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)
    }

    @Test
    fun `down upper-left with dominant west still resolves to HAT_NW`() {
        // dx=-30, dy=-12 → ratio 0.4 ≥ 0.3 → diagonal NW.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 120f, y = 238f), layout)
        assertEquals(GamepadTouchView.HAT_NW, recognizer.state.hatSwitch)
    }

    @Test
    fun `down on B button sets BTN_B`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        assertEquals(GamepadTouchView.BTN_B, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `down at cluster centre triggers all four buttons`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1000f, y = 1000f), layout)
        val all =
            GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
                GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y
        assertEquals(all, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `down between A and B in SE sector sets both bits`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1007.5f, y = 1007.5f), layout)
        val expected = GamepadTouchView.BTN_A or GamepadTouchView.BTN_B
        assertEquals(expected, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `drag from B in same direction past cluster keeps BTN_B`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1015f, y = 1000f), layout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 2000f, y = 1000f), layout)
        assertEquals(GamepadTouchView.BTN_B, recognizer.state.buttons and ABXY_MASK)
    }

    @Test
    fun `drag from B into A sector replaces BTN_B with BTN_A`() {
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
    fun `move with historical samples applies each intermediate position`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)
        assertEquals(GamepadTouchView.HAT_E, recognizer.state.hatSwitch)

        val sweep =
            moveEventWithHistory(
                history = listOf(150f to 280f, 120f to 250f),
                currentX = 150f,
                currentY = 220f,
            )
        val seen = mutableListOf<Int>()
        recognizer.onTouchEvent(sweep, layout) {
            seen.add(recognizer.state.hatSwitch)
        }

        assertEquals(listOf(GamepadTouchView.HAT_S, GamepadTouchView.HAT_W, GamepadTouchView.HAT_N), seen)
        assertEquals(GamepadTouchView.HAT_N, recognizer.state.hatSwitch)
    }

    @Test
    fun `move with no historical samples still fires callback once`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout)

        var callbackCount = 0
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 150f, y = 220f), layout) {
            callbackCount += 1
        }

        assertEquals(1, callbackCount)
        assertEquals(GamepadTouchView.HAT_N, recognizer.state.hatSwitch)
    }

    @Test
    fun `down fires callback exactly once`() {
        var callbackCount = 0
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 180f, y = 250f), layout) {
            callbackCount += 1
        }
        assertEquals(1, callbackCount)
    }

    @Test
    fun `cancel during ABXY hold clears every active bit`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1007.5f, y = 1007.5f), layout)
        val both = GamepadTouchView.BTN_A or GamepadTouchView.BTN_B
        assertEquals(both, recognizer.state.buttons and ABXY_MASK)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_CANCEL, x = 1007.5f, y = 1007.5f), layout)
        assertEquals(0, recognizer.state.buttons and ABXY_MASK)
    }

    private fun eventAt(
        actionMasked: Int,
        x: Float,
        y: Float,
        timeMs: Long,
        pid: Int = POINTER_0,
    ): MotionEvent =
        mockk {
            every { this@mockk.actionMasked } returns actionMasked
            every { actionIndex } returns 0
            every { pointerCount } returns 1
            every { getPointerId(0) } returns pid
            every { getX(0) } returns x
            every { getY(0) } returns y
            every { historySize } returns 0
            every { eventTime } returns timeMs
        }

    @Test
    fun `trackpad touch streams rect-normalised finger frames for two fingers`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 400f, y = 0f, timeMs = 1000L), trackpadLayout)

        assertTrue(recognizer.consumeTrackpadDirty())
        assertTrue(recognizer.trackpadState.finger0Active)
        assertEquals(Short.MIN_VALUE, recognizer.trackpadState.finger0X)
        assertEquals(Short.MIN_VALUE, recognizer.trackpadState.finger0Y)
        assertEquals(1000L, recognizer.trackpadState.eventTimeMs)

        recognizer.onTouchEvent(
            eventAt(MotionEvent.ACTION_POINTER_DOWN, x = 600f, y = 100f, timeMs = 1010L, pid = POINTER_1),
            trackpadLayout,
        )
        assertTrue(recognizer.consumeTrackpadDirty())
        assertTrue(recognizer.trackpadState.finger1Active)
        assertEquals(Short.MAX_VALUE, recognizer.trackpadState.finger1X)
        assertEquals(Short.MAX_VALUE, recognizer.trackpadState.finger1Y)
    }

    @Test
    fun `a quick still tap queues a click pulse at the touch position`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.trackpadTapSlopPx = 10f
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 450f, y = 25f, timeMs = 1000L), trackpadLayout)
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_UP, x = 450f, y = 25f, timeMs = 1120L), trackpadLayout)

        assertEquals(false, recognizer.trackpadState.finger0Active)
        val tap = recognizer.takePendingTrackpadTap()
        assertEquals((-16384).toShort(), tap?.x)
        assertEquals((-16384).toShort(), tap?.y)
        assertEquals(null, recognizer.takePendingTrackpadTap())
    }

    @Test
    fun `a drag past the slop never queues a click`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.trackpadTapSlopPx = 10f
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 450f, y = 50f, timeMs = 1000L), trackpadLayout)
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_MOVE, x = 480f, y = 50f, timeMs = 1050L), trackpadLayout)
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_UP, x = 480f, y = 50f, timeMs = 1100L), trackpadLayout)

        assertEquals(null, recognizer.takePendingTrackpadTap())
    }

    @Test
    fun `a slow press never queues a click`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.trackpadTapSlopPx = 10f
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 450f, y = 50f, timeMs = 1000L), trackpadLayout)
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_UP, x = 450f, y = 50f, timeMs = 1300L), trackpadLayout)

        assertEquals(null, recognizer.takePendingTrackpadTap())
    }

    @Test
    fun `click mode holds the touchpad button for the touch duration and emits no frames`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.CLICK
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 500f, y = 50f, timeMs = 1000L), trackpadLayout)

        assertEquals(GamepadTouchView.BTN_TOUCHPAD_CLICK, recognizer.state.buttons and GamepadTouchView.BTN_TOUCHPAD_CLICK)
        assertEquals(false, recognizer.consumeTrackpadDirty())

        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_UP, x = 500f, y = 50f, timeMs = 1100L), trackpadLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_TOUCHPAD_CLICK)
    }

    @Test
    fun `a trackpad lift never drops held centre buttons`() {
        val withSelect = trackpadLayout.copy(selectCx = 700f, centerBtnCy = 50f, smallBtnRadius = 10f)
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 700f, y = 50f, pid = POINTER_0), withSelect)
        assertEquals(GamepadTouchView.BTN_SELECT, recognizer.state.buttons and GamepadTouchView.BTN_SELECT)

        recognizer.onTouchEvent(
            eventAt(MotionEvent.ACTION_POINTER_DOWN, x = 500f, y = 50f, timeMs = 1000L, pid = POINTER_1),
            withSelect,
        )
        recognizer.onTouchEvent(
            eventAt(MotionEvent.ACTION_POINTER_UP, x = 500f, y = 50f, timeMs = 1050L, pid = POINTER_1),
            withSelect,
        )

        assertEquals(GamepadTouchView.BTN_SELECT, recognizer.state.buttons and GamepadTouchView.BTN_SELECT)
    }

    @Test
    fun `cancel lifts trackpad fingers with a dirty clean frame`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 500f, y = 50f, timeMs = 1000L), trackpadLayout)
        assertTrue(recognizer.consumeTrackpadDirty())

        recognizer.onTouchEvent(event(MotionEvent.ACTION_CANCEL, x = 500f, y = 50f), trackpadLayout)
        assertTrue(recognizer.consumeTrackpadDirty())
        assertEquals(false, recognizer.trackpadState.anyFingerDown())
    }

    // ── mic-mute pill (DualSense skin only) ────────────────────────────────

    // Pill spanning x 700..800, y 700..730, well clear of every other zone in [layout].
    private val muteLayout get() = layout.copy(micMuteRect = fakeRect(700f, 700f, 800f, 730f))

    @Test
    fun `the mute pill reports a momentary press and clears on release`() {
        // Momentary like the pad's own button: what the press toggles is the mute state the
        // overlay owns, so the bit itself must not stick.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 715f), muteLayout)
        assertEquals(GamepadTouchView.BTN_MIC_MUTE, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 750f, y = 715f), muteLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
    }

    @Test
    fun `a skin with no mute button never produces the bit`() {
        // layout carries micMuteRect = null, which is every skin but the DualSense.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 715f), layout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
    }

    @Test
    fun `at clamped geometry a press inside the pill wins over the home pickup halo`() {
        // A short screen clamps the pill up until it overlaps the home button's pickup circle
        // (smallBtnRadius x 1.5). The pill is a drawn rect and the halo is forgiveness around an
        // invisible boundary, so a finger inside the rect means the pill, always; testing home
        // first turned mute presses into PS-button presses and made the pill feel dead.
        val clamped =
            muteLayout.copy(
                homeCx = 750f,
                homeCy = 690f,
                smallBtnRadius = 20f,
            )
        // (750,705) is 15px from the home centre, well inside its 30px halo, AND inside the pill.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 705f), clamped)
        assertEquals(GamepadTouchView.BTN_MIC_MUTE, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_HOME)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 750f, y = 705f), clamped)

        // Above the pill the halo still works: the home button lost no reachable area.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 685f), clamped)
        assertEquals(GamepadTouchView.BTN_HOME, recognizer.state.buttons and GamepadTouchView.BTN_HOME)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
    }

    @Test
    fun `cancel drops a held mute press`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 715f), muteLayout)
        assertTrue(recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE != 0)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_CANCEL, x = 750f, y = 715f), muteLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
    }

    @Test
    fun `the mute pill claims nothing outside its own rect`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 699f, y = 715f), muteLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 699f, y = 715f), muteLayout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 731f), muteLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
    }

    @Test
    fun `the mute bit is local-only and cannot reach an XUSB wire report`() {
        // 1 shl 12 sits in the HID-layout word the view emits, where hidToXusb knows no such
        // button; the wire's own WBUTTON_MIC_MUTE is set from the mute STATE instead.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 715f), muteLayout)
        assertEquals(0, hidToXusb(recognizer.state.buttons, recognizer.state.hatSwitch))
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
