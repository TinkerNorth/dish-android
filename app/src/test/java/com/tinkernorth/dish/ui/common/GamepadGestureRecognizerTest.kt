// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.view.MotionEvent
import com.tinkernorth.dish.core.input.hidToXusb
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadGestureRecognizerTest {
    private val recognizer = GamepadGestureRecognizer()

    private val layout =
        GamepadLayout(
            dpadRect = box(100f, 200f, 200f, 300f),
            // ABXY centred at (1000,1000), btnRadius=10 → A(1000,1015) B(1015,1000)
            // X(985,1000) Y(1000,985); centre zone radius 7.5; midpoint A↔B at (1007.5,1007.5)
            // → distance 10.6, outside the centre zone. The rect reaches 20 past the centre so
            // the button centres are inside it (its right and bottom edges are exclusive).
            abxyRect = box(980f, 980f, 1020f, 1020f),
            lbRect = box(FAR, FAR, FAR + 1, FAR + 1),
            rbRect = box(FAR, FAR, FAR + 1, FAR + 1),
            ltRect = box(FAR, FAR, FAR + 1, FAR + 1),
            rtRect = box(FAR, FAR, FAR + 1, FAR + 1),
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
    private val trackpadLayout = layout.copy(trackpadRect = box(400f, 0f, 600f, 100f))

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
        val rail = box(0f, 100f, 52f, 500f)
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
        val rail = box(0f, 100f, 52f, 500f)
        val railLayout = layout.copy(ltRect = rail)
        recognizer.analogTriggers = false
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, 26f, 480f), railLayout)
        assertEquals(255, recognizer.state.leftTrigger)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, 26f, 480f), railLayout)
        assertEquals(0, recognizer.state.leftTrigger)
    }

    private fun box(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Box = Box(left = left, top = top, right = right, bottom = bottom)

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
            eventAt(MotionEvent.ACTION_POINTER_DOWN, x = 500f, y = 50f, timeMs = 1010L, pid = POINTER_1),
            trackpadLayout,
        )
        assertTrue(recognizer.consumeTrackpadDirty())
        assertTrue(recognizer.trackpadState.finger1Active)
        assertEquals(0.toShort(), recognizer.trackpadState.finger1X)
        assertEquals(0.toShort(), recognizer.trackpadState.finger1Y)
        assertEquals(1010L, recognizer.trackpadState.eventTimeMs)
    }

    @Test
    fun `a finger dragged past the trackpad edge pins to the edge`() {
        recognizer.trackpadMode = GamepadTouchView.TrackpadMode.TOUCH
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 500f, y = 50f, timeMs = 1000L), trackpadLayout)

        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_MOVE, x = 700f, y = 200f, timeMs = 1010L), trackpadLayout)
        assertEquals(Short.MAX_VALUE, recognizer.trackpadState.finger0X)
        assertEquals(Short.MAX_VALUE, recognizer.trackpadState.finger0Y)

        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_MOVE, x = 300f, y = -50f, timeMs = 1020L), trackpadLayout)
        assertEquals(Short.MIN_VALUE, recognizer.trackpadState.finger0X)
        assertEquals(Short.MIN_VALUE, recognizer.trackpadState.finger0Y)
    }

    @Test
    fun `the trackpad is inert when its mode is NONE`() {
        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_DOWN, x = 500f, y = 50f, timeMs = 1000L), trackpadLayout)
        assertFalse(recognizer.consumeTrackpadDirty())
        assertFalse(recognizer.trackpadState.anyFingerDown())

        recognizer.onTouchEvent(eventAt(MotionEvent.ACTION_UP, x = 500f, y = 50f, timeMs = 1050L), trackpadLayout)
        assertNull(recognizer.takePendingTrackpadTap())
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
    private val muteLayout get() = layout.copy(micMuteRect = box(700f, 700f, 800f, 730f))

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

    // ── shoulders ─────────────────────────────────────────────────────────

    private val shoulderLayout get() = layout.copy(lbRect = box(0f, 0f, 500f, 56f), rbRect = box(1500f, 0f, 2000f, 56f))

    @Test
    fun `a shoulder press sets its bit and releases by pointer id even after a drag off`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 100f, y = 28f, pid = POINTER_0), shoulderLayout)
        assertEquals(GamepadTouchView.BTN_LB, recognizer.state.buttons and SHOULDER_MASK)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, x = 1700f, y = 28f, pid = POINTER_1), shoulderLayout)
        assertEquals(SHOULDER_MASK, recognizer.state.buttons and SHOULDER_MASK)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 100f, y = 400f, pid = POINTER_0), shoulderLayout)
        assertEquals(SHOULDER_MASK, recognizer.state.buttons and SHOULDER_MASK)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP, x = 100f, y = 400f, pid = POINTER_0), shoulderLayout)
        assertEquals(GamepadTouchView.BTN_RB, recognizer.state.buttons and SHOULDER_MASK)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 1700f, y = 28f, pid = POINTER_1), shoulderLayout)
        assertEquals(0, recognizer.state.buttons and SHOULDER_MASK)
    }

    // ── sticks and stick clicks ───────────────────────────────────────────

    // Sticks of radius 50 at (300,600) and (1500,600), their clicks of radius 20 at (600,600)
    // and (1200,600): each pickup halo clear of every other zone in [layout].
    private val stickLayout get() =
        layout.copy(
            leftStickCx = 300f,
            leftStickCy = 600f,
            rightStickCx = 1500f,
            rightStickCy = 600f,
            stickRadius = 50f,
            l3StickCx = 600f,
            l3StickCy = 600f,
            r3StickCx = 1200f,
            r3StickCy = 600f,
            l3StickRadius = 20f,
        )

    @Test
    fun `a stick gesture drives the axes and releases to neutral`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 300f, y = 600f), stickLayout)
        assertEquals(0, recognizer.state.leftX.toInt())

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 350f, y = 600f), stickLayout)
        assertEquals(Short.MAX_VALUE, recognizer.state.leftX)
        assertEquals(0, recognizer.state.leftY.toInt())
        assertEquals(1f, recognizer.leftStickDx, AXIS_EPSILON)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 300f, y = 550f), stickLayout)
        assertEquals(Short.MAX_VALUE, recognizer.state.leftY)
        assertEquals(-1f, recognizer.leftStickDy, AXIS_EPSILON)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 300f, y = 550f), stickLayout)
        assertEquals(0, recognizer.state.leftX.toInt())
        assertEquals(0, recognizer.state.leftY.toInt())
        assertEquals(0f, recognizer.leftStickDx, 0f)
        assertEquals(0f, recognizer.leftStickDy, 0f)
    }

    @Test
    fun `the right stick drives the right axes`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1500f, y = 600f), stickLayout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 1550f, y = 600f), stickLayout)
        assertEquals(Short.MAX_VALUE, recognizer.state.rightX)
        assertEquals(0, recognizer.state.leftX.toInt())
        assertEquals(1f, recognizer.rightStickDx, AXIS_EPSILON)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 1550f, y = 600f), stickLayout)
        assertEquals(0, recognizer.state.rightX.toInt())
        assertEquals(0f, recognizer.rightStickDx, 0f)
    }

    @Test
    fun `a move from a pointer that did not claim the stick leaves it alone`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 300f, y = 600f, pid = POINTER_0), stickLayout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 350f, y = 600f, pid = POINTER_1), stickLayout)
        assertEquals(0, recognizer.state.leftX.toInt())
        assertEquals(0f, recognizer.leftStickDx, 0f)
    }

    @Test
    fun `an L3 press clicks and drives the left stick until release`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 600f, y = 600f), stickLayout)
        assertEquals(GamepadTouchView.BTN_LS, recognizer.state.buttons and GamepadTouchView.BTN_LS)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 620f, y = 600f), stickLayout)
        assertEquals(Short.MAX_VALUE, recognizer.state.leftX)
        assertEquals(1f, recognizer.l3StickDx, AXIS_EPSILON)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 620f, y = 600f), stickLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_LS)
        assertEquals(0, recognizer.state.leftX.toInt())
        assertEquals(0f, recognizer.l3StickDx, 0f)
    }

    @Test
    fun `an R3 press clicks and drives the right stick until release`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 1200f, y = 600f), stickLayout)
        assertEquals(GamepadTouchView.BTN_RS, recognizer.state.buttons and GamepadTouchView.BTN_RS)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 1220f, y = 600f), stickLayout)
        assertEquals(Short.MAX_VALUE, recognizer.state.rightX)
        assertEquals(1f, recognizer.r3StickDx, AXIS_EPSILON)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_UP, x = 1220f, y = 600f), stickLayout)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_RS)
        assertEquals(0, recognizer.state.rightX.toInt())
        assertEquals(0f, recognizer.r3StickDx, 0f)
    }

    @Test
    fun `a finger inside the stick halo is the stick even over a trigger rail`() {
        val railUnderStick = stickLayout.copy(ltRect = box(250f, 500f, 302f, 900f))
        // 40 below the stick centre: inside its 65 pickup halo, and inside the rail.
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 300f, y = 640f), railUnderStick)
        assertEquals(0, recognizer.state.leftTrigger)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 350f, y = 640f), railUnderStick)
        assertTrue(recognizer.state.leftX > 0)
    }

    // ── the buttons no pointer owns ───────────────────────────────────────

    // Select at (700,50), start at (800,50), home at (750,120), each with a 15px pickup halo.
    private val centreLayout get() =
        layout.copy(selectCx = 700f, startCx = 800f, homeCx = 750f, homeCy = 120f, centerBtnCy = 50f, smallBtnRadius = 10f)

    @Test
    fun `an untracked pointer lift releases every centre button`() {
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 700f, y = 50f, pid = POINTER_0), centreLayout)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, x = 800f, y = 50f, pid = POINTER_1), centreLayout)
        assertEquals(CENTRE_PAIR, recognizer.state.buttons and CENTRE_PAIR)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP, x = 700f, y = 50f, pid = POINTER_0), centreLayout)

        assertEquals(0, recognizer.state.buttons and CENTRE_PAIR)
    }

    @Test
    fun `an untracked pointer lift releases home and the mic mute`() {
        // The mute pill sits at (900..960, 40..60), clear of the three centre circles.
        val homeAndMute = centreLayout.copy(micMuteRect = box(900f, 40f, 960f, 60f))
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 750f, y = 120f, pid = POINTER_0), homeAndMute)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, x = 930f, y = 50f, pid = POINTER_1), homeAndMute)
        assertEquals(HOME_AND_MUTE, recognizer.state.buttons and HOME_AND_MUTE)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP, x = 750f, y = 120f, pid = POINTER_0), homeAndMute)

        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_HOME)
        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_MIC_MUTE)
    }

    @Test
    fun `a centre button lift leaves both held stick clicks alone`() {
        val clicksAndCentre = stickLayout.copy(selectCx = 700f, centerBtnCy = 50f, smallBtnRadius = 10f)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 600f, y = 600f, pid = POINTER_0), clicksAndCentre)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, x = 1200f, y = 600f, pid = POINTER_1), clicksAndCentre)
        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, x = 700f, y = 50f, pid = POINTER_2), clicksAndCentre)

        recognizer.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP, x = 700f, y = 50f, pid = POINTER_2), clicksAndCentre)

        assertEquals(0, recognizer.state.buttons and GamepadTouchView.BTN_SELECT)
        assertEquals(GamepadTouchView.BTN_LS, recognizer.state.buttons and GamepadTouchView.BTN_LS)
        assertEquals(GamepadTouchView.BTN_RS, recognizer.state.buttons and GamepadTouchView.BTN_RS)
    }

    private companion object {
        const val POINTER_0 = 0
        const val POINTER_1 = 1
        const val POINTER_2 = 2
        const val FAR = 10_000f
        const val AXIS_EPSILON = 1e-4f
        const val SHOULDER_MASK = GamepadTouchView.BTN_LB or GamepadTouchView.BTN_RB
        const val CENTRE_PAIR = GamepadTouchView.BTN_SELECT or GamepadTouchView.BTN_START
        const val HOME_AND_MUTE = GamepadTouchView.BTN_HOME or GamepadTouchView.BTN_MIC_MUTE

        val ABXY_MASK: Int =
            GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
                GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y
    }
}
