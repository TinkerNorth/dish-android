// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.architecture.testing.allocatedBytesDuring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A DualShock 4's surface as Android reports it unscaled.
private const val DS4_X_MAX = 1919f
private const val DS4_Y_MAX = 941f

private data class TestPointer(
    val id: Int,
    val x: Float,
    val y: Float,
)

// A captured event's pointers, index by index, on a DualShock 4's surface.
private class FakeTouchpadEvent(
    private val pointers: List<TestPointer>,
) : CapturedTouchpadEvent {
    override val pointerCount: Int get() = pointers.size

    override fun pointerId(index: Int): Int = pointers[index].id

    override fun x(index: Int): Float = pointers[index].x

    override fun y(index: Int): Float = pointers[index].y

    override val xMin: Float = 0f
    override val xMax: Float = DS4_X_MAX
    override val yMin: Float = 0f
    override val yMax: Float = DS4_Y_MAX
}

// An event whose pointers must not be read at all.
private class UnreadableTouchpadEvent : CapturedTouchpadEvent {
    override val pointerCount: Int get() = throw AssertionError("the pointer count was read")

    override fun pointerId(index: Int): Int = throw AssertionError("pointer $index was read")

    override fun x(index: Int): Float = throw AssertionError("pointer $index was read")

    override fun y(index: Int): Float = throw AssertionError("pointer $index was read")

    override val xMin: Float = 0f
    override val xMax: Float = DS4_X_MAX
    override val yMin: Float = 0f
    override val yMax: Float = DS4_Y_MAX
}

class CapturedTouchpadMapperTest {
    private fun eventOf(vararg pointers: TestPointer) = FakeTouchpadEvent(pointers.toList())

    private fun frameOf(
        event: CapturedTouchpadEvent,
        liftingIndex: Int = NO_POINTER_LIFTING,
        buttonPressed: Boolean = false,
        eventTimeMs: Long = EVENT_TIME_MS,
    ) = frame(event, liftingIndex, buttonPressed, eventTimeMs)

    // ---- the int16 span ----

    @Test
    fun `the near edge is the span's minimum and the far edge its maximum`() {
        assertEquals(Short.MIN_VALUE, normalize(0f, 0f, DS4_X_MAX))
        assertEquals(Short.MAX_VALUE, normalize(DS4_X_MAX, 0f, DS4_X_MAX))
    }

    @Test
    fun `the midpoint lands next to zero`() {
        // The exact value is the span's arithmetic, not a magic number, so only its neighbourhood is pinned.
        val mid = normalize(DS4_X_MAX / 2, 0f, DS4_X_MAX).toInt()
        assertTrue("mid $mid", mid in -1..1)
    }

    @Test
    fun `a reading past either edge clamps instead of wrapping`() {
        assertEquals(Short.MAX_VALUE, normalize(DS4_X_MAX + PAST_THE_EDGE, 0f, DS4_X_MAX))
        assertEquals(Short.MIN_VALUE, normalize(-PAST_THE_EDGE, 0f, DS4_X_MAX))
    }

    @Test
    fun `a range that does not start at zero is read from its minimum`() {
        assertEquals(Short.MIN_VALUE, normalize(OFFSET_MIN, OFFSET_MIN, OFFSET_MAX))
        assertEquals(Short.MAX_VALUE, normalize(OFFSET_MAX, OFFSET_MIN, OFFSET_MAX))
    }

    @Test
    fun `an empty or inverted range reads zero instead of dividing by it`() {
        assertEquals(0.toShort(), normalize(OFFSET_MAX, OFFSET_MIN, OFFSET_MIN))
        assertEquals(0.toShort(), normalize(OFFSET_MAX, OFFSET_MAX, OFFSET_MIN))
    }

    // ---- which pointers fill the two slots ----

    @Test
    fun `one finger fills slot 0 and leaves slot 1 lifted`() {
        val frame = frameOf(eventOf(TestPointer(3, 0f, DS4_Y_MAX)))
        assertTrue(frame.finger0Active)
        assertFalse(frame.finger1Active)
        assertEquals(3, frame.finger0Id)
        assertEquals(Short.MIN_VALUE, frame.finger0X)
        assertEquals(Short.MAX_VALUE, frame.finger0Y)
        assertEquals(0, frame.finger1Id)
        assertEquals(0.toShort(), frame.finger1X)
        assertEquals(0.toShort(), frame.finger1Y)
    }

    @Test
    fun `the frame carries the event's time and click`() {
        val frame = frameOf(eventOf(TestPointer(3, 0f, 0f)), buttonPressed = true, eventTimeMs = OTHER_TIME_MS)
        assertEquals(OTHER_TIME_MS, frame.eventTimeMs)
        assertTrue(frame.buttonPressed)
    }

    @Test
    fun `fingers take slots in pointer-id order and a third is ignored`() {
        val frame =
            frameOf(
                eventOf(TestPointer(9, DS4_X_MAX, 0f), TestPointer(2, 0f, DS4_Y_MAX), TestPointer(11, 10f, 10f)),
            )
        assertTrue(frame.finger0Active)
        assertTrue(frame.finger1Active)
        assertEquals(2, frame.finger0Id)
        assertEquals(Short.MIN_VALUE, frame.finger0X)
        assertEquals(Short.MAX_VALUE, frame.finger0Y)
        assertEquals(9, frame.finger1Id)
        assertEquals(Short.MAX_VALUE, frame.finger1X)
        assertEquals(Short.MIN_VALUE, frame.finger1Y)
    }

    @Test
    fun `the lowest ids win wherever they sit in the event`() {
        val frame = frameOf(eventOf(TestPointer(11, 0f, 0f), TestPointer(9, 0f, 0f), TestPointer(2, 0f, 0f)))
        assertEquals(2, frame.finger0Id)
        assertEquals(9, frame.finger1Id)
    }

    @Test
    fun `tracking ids are masked to the seven bits the pad's own report carries`() {
        val frame = frameOf(eventOf(TestPointer(FIRST_WIDE_ID, 0f, 0f), TestPointer(SECOND_WIDE_ID, 0f, 0f)))
        assertEquals(FIRST_WIDE_ID and TRACKING_ID_MASK, frame.finger0Id)
        assertEquals(SECOND_WIDE_ID and TRACKING_ID_MASK, frame.finger1Id)
    }

    @Test
    fun `no fingers is an all-lifted frame that still carries the click`() {
        val frame = frameOf(eventOf(), buttonPressed = true)
        assertFalse(frame.anyFingerDown())
        assertTrue(frame.buttonPressed)
    }

    // ---- the pointer an event takes off ----

    @Test
    fun `lifting the lower id moves the other finger to slot 0`() {
        val frame = frameOf(eventOf(TestPointer(2, 0f, 0f), TestPointer(9, DS4_X_MAX, 0f)), liftingIndex = 0)
        assertTrue(frame.finger0Active)
        assertFalse(frame.finger1Active)
        assertEquals(9, frame.finger0Id)
        assertEquals(Short.MAX_VALUE, frame.finger0X)
    }

    @Test
    fun `lifting the higher id leaves the lower one in slot 0`() {
        val frame = frameOf(eventOf(TestPointer(2, 0f, 0f), TestPointer(9, DS4_X_MAX, 0f)), liftingIndex = 1)
        assertTrue(frame.finger0Active)
        assertFalse(frame.finger1Active)
        assertEquals(2, frame.finger0Id)
    }

    @Test
    fun `an event that lifts every pointer is all lifted and never reads one`() {
        val frame = frameOf(UnreadableTouchpadEvent(), liftingIndex = EVERY_POINTER_LIFTING, buttonPressed = true)
        assertFalse(frame.anyFingerDown())
        assertTrue(frame.buttonPressed)
    }

    @Test
    fun `lifted keeps the positions and drops every finger and the click`() {
        val down = frameOf(eventOf(TestPointer(1, DS4_X_MAX, 0f)), buttonPressed = true)
        val lifted = down.lifted(OTHER_TIME_MS)
        assertFalse(lifted.anyFingerDown())
        assertFalse(lifted.buttonPressed)
        assertEquals(down.finger0X, lifted.finger0X)
        assertEquals(OTHER_TIME_MS, lifted.eventTimeMs)
    }

    // ---- per event, the frame is the only thing built ----

    private var kept = PadTouchFrame()

    private val threeFingers =
        eventOf(TestPointer(9, DS4_X_MAX, 0f), TestPointer(2, 0f, DS4_Y_MAX), TestPointer(11, 10f, 10f))

    private fun mapEvents() {
        repeat(MEASURED_EVENTS) { kept = frameOf(threeFingers, eventTimeMs = it.toLong()) }
    }

    // The same frame, built directly: what the mapping may cost and no more.
    private fun buildFrames() {
        repeat(MEASURED_EVENTS) { kept = PadTouchFrame(finger0Active = true, eventTimeMs = it.toLong()) }
    }

    @Test
    fun `mapping a captured event allocates nothing but the frame it keeps`() {
        repeat(WARMUP_ROUNDS) {
            mapEvents()
            buildFrames()
        }
        val frames = allocatedBytesDuring(::buildFrames)
        val mapped = allocatedBytesDuring(::mapEvents)
        assertTrue("mapped $mapped bytes, frames alone $frames", mapped < frames + MEASURED_EVENTS * BYTES_PER_EVENT_BOUND)
    }

    private companion object {
        const val EVENT_TIME_MS = 42L
        const val OTHER_TIME_MS = 9L
        const val PAST_THE_EDGE = 500f
        const val OFFSET_MIN = 100f
        const val OFFSET_MAX = 300f
        const val FIRST_WIDE_ID = 0x1F3
        const val SECOND_WIDE_ID = 0x2F4
        const val WARMUP_ROUNDS = 10
        const val MEASURED_EVENTS = 1000

        // Half the smallest object: one allocation per event beyond the frame costs 16 bytes or
        // more every event, while the JIT's one-off warm-up allocations stay flat as events grow.
        const val BYTES_PER_EVENT_BOUND = 8
    }
}
