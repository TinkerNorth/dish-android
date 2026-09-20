// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.hotpath.input.CapturedTouchpadMapper.Pointer
import com.tinkernorth.dish.hotpath.input.CapturedTouchpadMapper.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedTouchpadMapperTest {
    // A DualShock 4's surface as Android reports it unscaled.
    private val ds4X = Range(0f, 1919f)
    private val ds4Y = Range(0f, 941f)

    @Test
    fun `normalize maps the surface onto the wire's int16 span like the raw decoder`() {
        assertEquals(Short.MIN_VALUE, CapturedTouchpadMapper.normalize(0f, ds4X))
        assertEquals(Short.MAX_VALUE, CapturedTouchpadMapper.normalize(1919f, ds4X))
        // The midpoint lands near zero; the exact value is the span's arithmetic, not a magic
        // number, so only its neighbourhood is pinned.
        val mid = CapturedTouchpadMapper.normalize(959.5f, ds4X).toInt()
        assertTrue("mid $mid", mid in -1..1)
        // A jittery reading past the range clamps instead of wrapping.
        assertEquals(Short.MAX_VALUE, CapturedTouchpadMapper.normalize(2500f, ds4X))
        assertEquals(Short.MIN_VALUE, CapturedTouchpadMapper.normalize(-3f, ds4X))
        // A degenerate range cannot divide by zero.
        assertEquals(0.toShort(), CapturedTouchpadMapper.normalize(5f, Range(7f, 7f)))
    }

    @Test
    fun `one finger fills slot 0 and leaves slot 1 lifted`() {
        val frame = CapturedTouchpadMapper.frame(listOf(Pointer(3, 0f, 941f)), ds4X, ds4Y, buttonPressed = false, eventTimeMs = 42L)
        assertTrue(frame.finger0Active)
        assertFalse(frame.finger1Active)
        assertEquals(3, frame.finger0Id)
        assertEquals(Short.MIN_VALUE, frame.finger0X)
        assertEquals(Short.MAX_VALUE, frame.finger0Y)
        assertEquals(0, frame.finger1Id)
        assertEquals(42L, frame.eventTimeMs)
        assertFalse(frame.buttonPressed)
    }

    @Test
    fun `fingers take slots in pointer-id order and a third is ignored`() {
        val frame =
            CapturedTouchpadMapper.frame(
                listOf(Pointer(9, 1919f, 0f), Pointer(2, 0f, 0f), Pointer(11, 10f, 10f)),
                ds4X,
                ds4Y,
                buttonPressed = true,
                eventTimeMs = 1L,
            )
        assertTrue(frame.finger0Active && frame.finger1Active)
        assertEquals(2, frame.finger0Id)
        assertEquals(9, frame.finger1Id)
        assertEquals(Short.MAX_VALUE, frame.finger1X)
        assertTrue(frame.buttonPressed)
    }

    @Test
    fun `tracking ids are masked to the seven bits the pad's own report carries`() {
        val frame = CapturedTouchpadMapper.frame(listOf(Pointer(0x1F3, 0f, 0f)), ds4X, ds4Y, buttonPressed = false, eventTimeMs = 0L)
        assertEquals(0x73, frame.finger0Id)
    }

    @Test
    fun `no fingers is an all-lifted frame that still carries the click`() {
        val frame = CapturedTouchpadMapper.frame(emptyList(), ds4X, ds4Y, buttonPressed = true, eventTimeMs = 5L)
        assertFalse(frame.anyFingerDown())
        assertTrue(frame.buttonPressed)
    }

    @Test
    fun `lifted keeps the positions and drops every finger and the click`() {
        val down = CapturedTouchpadMapper.frame(listOf(Pointer(1, 100f, 100f)), ds4X, ds4Y, buttonPressed = true, eventTimeMs = 7L)
        val lifted = down.lifted(9L)
        assertFalse(lifted.anyFingerDown())
        assertFalse(lifted.buttonPressed)
        assertEquals(down.finger0X, lifted.finger0X)
        assertEquals(9L, lifted.eventTimeMs)
    }
}
