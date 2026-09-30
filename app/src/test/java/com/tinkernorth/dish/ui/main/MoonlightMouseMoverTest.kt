// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.architecture.testing.allocatedBytesDuring
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_LEFT
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_MIDDLE
import com.tinkernorth.dish.core.net.moonlight.MOUSE_BUTTON_RIGHT
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// One packet as the host would receive it.
private sealed interface MouseCommand {
    data class Button(
        val down: Boolean,
        val button: Int,
    ) : MouseCommand

    data class Scroll(
        val amount: Int,
    ) : MouseCommand

    data class MoveRel(
        val dx: Int,
        val dy: Int,
    ) : MouseCommand
}

private class RecordingSink : MoonlightMouseSink {
    private val sent = mutableListOf<MouseCommand>()

    override fun sendMouseButton(
        down: Boolean,
        button: Int,
    ) {
        sent.add(MouseCommand.Button(down, button))
    }

    override fun sendMouseScroll(amount: Int) {
        sent.add(MouseCommand.Scroll(amount))
    }

    override fun sendMouseMoveRel(
        dx: Int,
        dy: Int,
    ) {
        sent.add(MouseCommand.MoveRel(dx, dy))
    }

    fun drain(): List<MouseCommand> {
        val drained = sent.toList()
        sent.clear()
        return drained
    }
}

// Counts packets without keeping them, so the allocation test measures only the mover.
private class CountingSink : MoonlightMouseSink {
    var sent = 0
        private set

    override fun sendMouseButton(
        down: Boolean,
        button: Int,
    ) {
        sent++
    }

    override fun sendMouseScroll(amount: Int) {
        sent++
    }

    override fun sendMouseMoveRel(
        dx: Int,
        dy: Int,
    ) {
        sent++
    }
}

// The mouse surface's frames as Moonlight's edge-triggered packets.
class MoonlightMouseMoverTest {
    private val mover = MoonlightMouseMover()
    private val sink = RecordingSink()

    private fun lifted() = TouchpadSurfaceView.TouchpadState()

    private fun finger(
        x: Int,
        y: Int,
        trackingId: Int = 1,
    ) = TouchpadSurfaceView.TouchpadState(
        finger0Active = true,
        finger0TrackingId = trackingId,
        finger0X = x.toShort(),
        finger0Y = y.toShort(),
    )

    private fun frame(
        fingers: TouchpadSurfaceView.TouchpadState = lifted(),
        scrollNotches: Int = 0,
        left: Boolean = false,
        right: Boolean = false,
        middle: Boolean = false,
    ): List<MouseCommand> {
        mover.onFrame(sink, fingers, scrollNotches, left, right, middle)
        return sink.drain()
    }

    private fun release(): List<MouseCommand> {
        mover.releaseButtons(sink)
        return sink.drain()
    }

    @Test
    fun `a button is sent on its edges only`() {
        assertEquals(listOf(MouseCommand.Button(true, MOUSE_BUTTON_LEFT)), frame(left = true))
        assertTrue(frame(left = true).isEmpty())
        assertEquals(listOf(MouseCommand.Button(false, MOUSE_BUTTON_LEFT)), frame(left = false))
    }

    @Test
    fun `the three buttons go out left, right, middle`() {
        assertEquals(
            listOf(
                MouseCommand.Button(true, MOUSE_BUTTON_LEFT),
                MouseCommand.Button(true, MOUSE_BUTTON_RIGHT),
                MouseCommand.Button(true, MOUSE_BUTTON_MIDDLE),
            ),
            frame(left = true, right = true, middle = true),
        )
    }

    @Test
    fun `scroll notches become wheel units and clamp to int16`() {
        assertEquals(listOf(MouseCommand.Scroll(WHEEL_UNITS_PER_NOTCH)), frame(scrollNotches = 1))
        assertEquals(listOf(MouseCommand.Scroll(-WHEEL_UNITS_PER_NOTCH)), frame(scrollNotches = -1))
        assertEquals(listOf(MouseCommand.Scroll(Short.MAX_VALUE.toInt())), frame(scrollNotches = 1000))
        assertEquals(listOf(MouseCommand.Scroll(Short.MIN_VALUE.toInt())), frame(scrollNotches = -1000))
        assertEquals(Short.MAX_VALUE.toInt(), wheelDeltaFor(1000))
    }

    @Test
    fun `a fresh touch anchors without moving`() {
        assertTrue(frame(finger(1000, 1000)).isEmpty())
    }

    @Test
    fun `a drag moves relative to the anchor`() {
        frame(finger(0, 0))
        assertEquals(listOf(MouseCommand.MoveRel(27, 0)), frame(finger(1000, 0)))
    }

    @Test
    fun `slow drags accumulate until a whole pixel`() {
        frame(finger(0, 0))
        assertTrue(frame(finger(10, 0)).isEmpty())
        assertTrue(frame(finger(20, 0)).isEmpty())
        assertEquals(listOf(MouseCommand.MoveRel(1, 0)), frame(finger(40, 0)))
    }

    // Each 1000-unit step is 27.47 host pixels: whole pixels go out and the fractions add up to
    // an extra pixel on the third step.
    @Test
    fun `the remainder carries across moves instead of being dropped`() {
        frame(finger(0, 0))
        frame(finger(1000, 0))
        frame(finger(2000, 0))
        assertEquals(listOf(MouseCommand.MoveRel(28, 0)), frame(finger(3000, 0)))
    }

    @Test
    fun `the vertical remainder carries across moves instead of being dropped`() {
        frame(finger(0, 0))
        frame(finger(0, 1000))
        frame(finger(0, 2000))
        assertEquals(listOf(MouseCommand.MoveRel(0, 28)), frame(finger(0, 3000)))
    }

    @Test
    fun `lifting the finger resets the anchor`() {
        frame(finger(0, 0))
        assertTrue(frame(lifted()).isEmpty())
        assertTrue(frame(finger(1000, 1000)).isEmpty())
        assertEquals(listOf(MouseCommand.MoveRel(27, 27)), frame(finger(2000, 2000)))
    }

    @Test
    fun `a new tracking id is a fresh touch even without a lift`() {
        frame(finger(0, 0, trackingId = 1))
        assertTrue(frame(finger(1000, 1000, trackingId = 2)).isEmpty())
    }

    @Test
    fun `leaving the surface releases every held button once`() {
        frame(left = true, middle = true)
        assertEquals(
            listOf(MouseCommand.Button(false, MOUSE_BUTTON_LEFT), MouseCommand.Button(false, MOUSE_BUTTON_MIDDLE)),
            release(),
        )
        assertTrue(release().isEmpty())
    }

    @Test
    fun `a frame after a release re-sends a still-held button`() {
        frame(left = true)
        release()
        assertEquals(listOf(MouseCommand.Button(true, MOUSE_BUTTON_LEFT)), frame(left = true))
    }

    // One cycle walks every edge the mover has: an anchor, a drag, both scroll signs, every
    // button down and up, a lift, and a release with buttons held.
    private fun runFrameCycle(
        counter: CountingSink,
        anchor: TouchpadSurfaceView.TouchpadState,
        drag: TouchpadSurfaceView.TouchpadState,
        lifted: TouchpadSurfaceView.TouchpadState,
    ) {
        mover.onFrame(counter, anchor, 1, leftHeld = true, rightHeld = false, middleHeld = false)
        mover.onFrame(counter, drag, 0, leftHeld = false, rightHeld = true, middleHeld = true)
        mover.onFrame(counter, anchor, -1, leftHeld = false, rightHeld = false, middleHeld = false)
        mover.onFrame(counter, lifted, 0, leftHeld = true, rightHeld = true, middleHeld = true)
        mover.releaseButtons(counter)
    }

    @Test
    fun `a touch frame allocates nothing`() {
        val counter = CountingSink()
        val anchor = finger(0, 0)
        val drag = finger(DRAG_UNITS, DRAG_UNITS)
        val lifted = lifted()
        repeat(WARMUP_CYCLES) { runFrameCycle(counter, anchor, drag, lifted) }
        val sentBefore = counter.sent
        val allocatedBytes = allocatedBytesDuring { repeat(MEASURED_CYCLES) { runFrameCycle(counter, anchor, drag, lifted) } }
        assertEquals(MEASURED_CYCLES * PACKETS_PER_CYCLE, counter.sent - sentBefore)
        assertTrue("$allocatedBytes bytes over $MEASURED_CYCLES cycles", allocatedBytes < MEASURED_CYCLES * BYTES_PER_CYCLE_BOUND)
    }

    @Test
    fun `with no sink held one is made for the connection`() {
        val connection = mockk<MoonlightConnection>(relaxed = true)
        assertSame(connection, moonlightMouseSinkFor(held = null, connection).connection)
    }

    @Test
    fun `frames on the same connection reuse its sink`() {
        val connection = mockk<MoonlightConnection>(relaxed = true)
        val held = moonlightMouseSinkFor(held = null, connection)
        assertSame(held, moonlightMouseSinkFor(held, connection))
    }

    @Test
    fun `a reconnect's new connection gets its own sink`() {
        val lost = mockk<MoonlightConnection>(relaxed = true)
        val reconnected = mockk<MoonlightConnection>(relaxed = true)
        val held = moonlightMouseSinkFor(held = null, lost)
        val next = moonlightMouseSinkFor(held, reconnected)
        assertNotSame(held, next)
        assertSame(reconnected, next.connection)
    }

    @Test
    fun `the connection sink forwards every packet unchanged`() {
        val connection = mockk<MoonlightConnection>(relaxed = true)
        val connectionSink = moonlightMouseSinkFor(held = null, connection)
        connectionSink.sendMouseButton(true, MOUSE_BUTTON_RIGHT)
        connectionSink.sendMouseScroll(-WHEEL_UNITS_PER_NOTCH)
        connectionSink.sendMouseMoveRel(FORWARDED_DX, FORWARDED_DY)
        verify(exactly = 1) { connection.sendMouseButton(true, MOUSE_BUTTON_RIGHT) }
        verify(exactly = 1) { connection.sendMouseScroll(-WHEEL_UNITS_PER_NOTCH) }
        verify(exactly = 1) { connection.sendMouseMoveRel(FORWARDED_DX, FORWARDED_DY) }
    }

    private companion object {
        const val FORWARDED_DX = 3
        const val FORWARDED_DY = -4
        const val WHEEL_UNITS_PER_NOTCH = 120
        const val DRAG_UNITS = 1000
        const val WARMUP_CYCLES = 10
        const val MEASURED_CYCLES = 1000

        // Half the smallest object: one allocation in any frame costs 16 bytes or more every
        // cycle, while the JIT's one-off warm-up allocations stay flat as the cycles grow.
        const val BYTES_PER_CYCLE_BOUND = 8

        // Frame by frame: left down + scroll; move + left up, right and middle down; move back,
        // right and middle up, scroll; every button down on the lift; three releases.
        const val PACKETS_PER_CYCLE = 2 + 4 + 4 + 3 + 3
    }
}
