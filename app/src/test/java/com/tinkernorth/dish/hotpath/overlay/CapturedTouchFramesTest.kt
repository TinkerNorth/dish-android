// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.overlay

import com.tinkernorth.dish.architecture.testing.fewestAllocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.freshAppInstanceOf
import com.tinkernorth.dish.hotpath.input.CapturedSurfaceTable
import com.tinkernorth.dish.hotpath.input.CapturedTouchpadEvent
import com.tinkernorth.dish.hotpath.input.EVERY_POINTER_LIFTING
import com.tinkernorth.dish.hotpath.input.NO_POINTER_LIFTING
import com.tinkernorth.dish.hotpath.input.TRACKING_ID_MASK
import com.tinkernorth.dish.hotpath.input.capturedSurfaceTableOf
import com.tinkernorth.dish.hotpath.input.normalize
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.source.connection.TouchpadReport
import com.tinkernorth.dish.ui.common.ResendPacer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier

// A DualShock 4's surface as Android reports it unscaled.
private const val SURFACE_X_MAX = 1919f
private const val SURFACE_Y_MAX = 941f

// The event number [step] of a stream in which every field derives from that number, so a frame
// read whole names one step and a torn one mixes two.
private fun pointerIdAt(step: Long): Int = (step and TRACKING_ID_MASK.toLong()).toInt()

private fun xAt(step: Long): Float = (step % SURFACE_X_MAX.toLong()).toFloat()

private fun yAt(step: Long): Float = (step % SURFACE_Y_MAX.toLong()).toFloat()

// Two fingers, both placed by the step the event is at; the second one's id is one past the first.
private class SteppedTouchpadEvent : CapturedTouchpadEvent {
    var step = 0L

    override val pointerCount: Int get() = 2

    override fun pointerId(index: Int): Int = pointerIdAt(step) + index * (TRACKING_ID_MASK + 1)

    override fun x(index: Int): Float = xAt(step)

    override fun y(index: Int): Float = yAt(step)

    override val xMin: Float get() = 0f
    override val xMax: Float get() = SURFACE_X_MAX
    override val yMin: Float get() = 0f
    override val yMax: Float get() = SURFACE_Y_MAX
}

// Counts the frames it is handed and keeps nothing of them.
private class CountingSink : TelemetrySink {
    val sent = AtomicLong()

    override fun sendMotion(
        slotId: String,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    ) = Unit

    override fun sendBattery(
        slotId: String,
        level: Int,
        status: Int,
    ) = Unit

    override fun sendTouchpad(
        slotId: String,
        report: TouchpadReport,
    ) {
        sent.incrementAndGet()
    }
}

// An enum only for MockK to mock. A relaxed mock that hands out an enum anywhere in the suite
// makes MockK rewrite java.lang.Enum for the rest of the JVM, and every ordinal() then allocates;
// a JDK class has no fresh copy to measure instead (freshAppInstanceOf), so the frame path must
// not lean on one, and the allocation tests mock this first to hold it to that whatever ran before.
private enum class RewrittenEnum {
    ONLY,
}

private const val FIRST_SLOT = "7"
private const val SECOND_SLOT = "8"
private const val EVENTS_PER_CYCLE = 1000
private const val TICKS_PER_CYCLE = 1000

// A stream of captured events on two routed slots, each sent at once. Reached through Runnable
// (one cycle) and LongSupplier (the frames sent) because the allocation test makes it in a class
// loader of its own (freshAppInstanceOf).
internal class CapturedEventCycles :
    Runnable,
    LongSupplier {
    private val sink = CountingSink()
    private val frames = CapturedTouchFrames { sink }
    private val event = SteppedTouchpadEvent()

    override fun run() {
        repeat(EVENTS_PER_CYCLE) {
            event.step++
            val slotId = if (event.step % 2 == 0L) FIRST_SLOT else SECOND_SLOT
            frames.onCaptured(slotId, event, NO_POINTER_LIFTING, buttonPressed = event.step % 3 == 0L, eventTimeMs = event.step)
        }
    }

    override fun getAsLong(): Long = sink.sent.get()
}

// The resend thread's ticks over two routed slots, each holding a frame that changes every so
// often, so the ticks cross bursts, keepalives and quiet ticks alike.
internal class ResendTickCycles :
    Runnable,
    LongSupplier {
    private val sink = CountingSink()
    private val frames = CapturedTouchFrames { sink }
    private val event = SteppedTouchpadEvent()
    private val routed = capturedSurfaceTableOf(mapOf(31 to FIRST_SLOT, 32 to SECOND_SLOT))
    private var ticks = 0L

    override fun run() {
        repeat(TICKS_PER_CYCLE) {
            if (it % CHANGE_EVERY_TICKS == 0) capture()
            if (frames.resendDue(routed)) ticks++
        }
    }

    private fun capture() {
        event.step++
        frames.onCaptured(FIRST_SLOT, event, NO_POINTER_LIFTING, buttonPressed = false, eventTimeMs = event.step)
        frames.onCaptured(SECOND_SLOT, event, NO_POINTER_LIFTING, buttonPressed = true, eventTimeMs = event.step)
    }

    override fun getAsLong(): Long = ticks

    private companion object {
        const val CHANGE_EVERY_TICKS = 50
    }
}

class CapturedTouchFramesTest {
    // ---- what a lost focus or a released capture lifts ----

    private val recording = CopyingSink()
    private val frames = CapturedTouchFrames { slotId -> recording.takeIf { slotId != UNREACHABLE_SLOT } }
    private val event = SteppedTouchpadEvent()
    private val routedFirst = capturedSurfaceTableOf(mapOf(31 to FIRST_SLOT))
    private val routedNone = capturedSurfaceTableOf(emptyMap())

    private fun capture(
        slotId: String = FIRST_SLOT,
        liftingIndex: Int = NO_POINTER_LIFTING,
        buttonPressed: Boolean = false,
    ) {
        event.step++
        frames.onCaptured(slotId, event, liftingIndex, buttonPressed, eventTimeMs = event.step)
    }

    @Test
    fun `a frame with no finger and no click is not lifted`() {
        capture(liftingIndex = EVERY_POINTER_LIFTING)

        frames.liftAll(LIFT_TIME_MS)

        assertEquals(1, recording.frames.size)
    }

    @Test
    fun `a frame holding a finger is lifted at the given time, where the finger was`() {
        capture()

        frames.liftAll(LIFT_TIME_MS)

        val (held, lifted) = recording.frames
        assertEquals(held.copy(finger0Active = false, finger1Active = false, eventTimeMs = LIFT_TIME_MS), lifted)
    }

    @Test
    fun `a frame holding only the click is lifted too`() {
        capture(liftingIndex = EVERY_POINTER_LIFTING, buttonPressed = true)

        frames.liftAll(LIFT_TIME_MS)

        assertEquals(2, recording.frames.size)
        assertFalse(recording.frames[1].buttonPressed)
    }

    @Test
    fun `a lifted slot is lifted once`() {
        capture()

        frames.liftAll(LIFT_TIME_MS)
        frames.liftAll(LIFT_TIME_MS + 1)

        assertEquals(2, recording.frames.size)
    }

    @Test
    fun `a lift goes out again on the next tick, as the change it is`() {
        capture()
        frames.liftAll(LIFT_TIME_MS)

        frames.resendDue(routedFirst)

        assertEquals(recording.frames[1], recording.frames[2])
    }

    @Test
    fun `a slot with no sink is lifted but nothing is sent`() {
        capture(slotId = UNREACHABLE_SLOT)

        frames.liftAll(LIFT_TIME_MS)

        assertTrue(recording.frames.isEmpty())
    }

    // ---- what one resend tick holds, sends and forgets ----

    private fun tickPastTheBurst(routed: CapturedSurfaceTable): Boolean {
        var holdsAFrame = false
        repeat(ResendPacer.EDGE_BURST_RESENDS + 1) { holdsAFrame = frames.resendDue(routed) }
        return holdsAFrame
    }

    @Test
    fun `no captured frame is none held`() {
        assertFalse(frames.resendDue(routedFirst))
    }

    @Test
    fun `a captured frame is resent through its burst and held after it`() {
        capture()

        assertTrue(tickPastTheBurst(routedFirst))

        assertEquals(1 + ResendPacer.EDGE_BURST_RESENDS, recording.frames.size)
        assertTrue(recording.frames.all { it == recording.frames[0] })
    }

    @Test
    fun `a slot no route names is forgotten once its burst is out, and then neither held nor lifted`() {
        capture()

        assertFalse(tickPastTheBurst(routedNone))
        assertFalse(frames.resendDue(routedNone))
        frames.liftAll(LIFT_TIME_MS)

        assertEquals(1 + ResendPacer.EDGE_BURST_RESENDS, recording.frames.size)
    }

    @Test
    fun `a forgotten slot's next frame holds it again, as a change`() {
        capture()
        tickPastTheBurst(routedNone)

        capture()

        assertTrue(frames.resendDue(routedFirst))
        val sent = recording.frames
        assertEquals(sent[sent.size - 2], sent.last())
    }

    // Forgetting drops what the slot last resent, so even the very frame it held before is a
    // change when it comes back, and gets its burst.
    @Test
    fun `a forgotten slot's next frame is a change even when it equals the one before`() {
        capture()
        tickPastTheBurst(routedNone)
        val sentBefore = recording.frames.size

        frames.onCaptured(FIRST_SLOT, event, NO_POINTER_LIFTING, buttonPressed = false, eventTimeMs = event.step)
        frames.resendDue(routedFirst)

        assertEquals(sentBefore + 2, recording.frames.size)
    }

    @Test
    fun `a slot with no sink is kept through its burst and sends nothing`() {
        capture(slotId = UNREACHABLE_SLOT)

        assertTrue(tickPastTheBurst(capturedSurfaceTableOf(mapOf(31 to UNREACHABLE_SLOT))))

        assertTrue(recording.frames.isEmpty())
    }

    // ---- nothing is built per event or per tick ----

    @Test
    fun `a captured event allocates nothing, even once MockK has rewritten java lang Enum`() {
        mockk<RewrittenEnum>(relaxed = true).ordinal
        val cycles = freshAppInstanceOf(CapturedEventCycles::class.java)
        val cycle = cycles as Runnable
        repeat(WARMUP_CYCLES) { cycle.run() }

        val allocated = fewestAllocatedBytesDuring(MEASURED_RUNS) { cycle.run() }

        assertEquals(((WARMUP_CYCLES + MEASURED_RUNS) * EVENTS_PER_CYCLE).toLong(), (cycles as LongSupplier).asLong)
        assertTrue("$allocated bytes over $EVENTS_PER_CYCLE events", allocated < EVENTS_PER_CYCLE * BYTES_PER_STEP_BOUND)
    }

    @Test
    fun `a resend tick allocates nothing, even once MockK has rewritten java lang Enum`() {
        mockk<RewrittenEnum>(relaxed = true).ordinal
        val cycles = freshAppInstanceOf(ResendTickCycles::class.java)
        val cycle = cycles as Runnable
        repeat(WARMUP_CYCLES) { cycle.run() }

        val allocated = fewestAllocatedBytesDuring(MEASURED_RUNS) { cycle.run() }

        val everyTick = ((WARMUP_CYCLES + MEASURED_RUNS) * TICKS_PER_CYCLE).toLong()
        assertEquals("every tick held a frame", everyTick, (cycles as LongSupplier).asLong)
        assertTrue("$allocated bytes over $TICKS_PER_CYCLE ticks", allocated < TICKS_PER_CYCLE * BYTES_PER_STEP_BOUND)
    }

    // ---- the resend thread reads each frame whole ----

    // The main thread captures one event after another while the resend thread ticks. Every frame
    // a tick sends must be one the main thread captured, never half of one and half of the next.
    @Test
    fun `a resend tick never sends a frame torn between two captured events`() {
        val torn = AtomicReference<String?>(null)
        val resent = AtomicLong()
        val sink = WholeFrameSink(torn, resent)
        val frames = CapturedTouchFrames { sink }
        val routed = capturedSurfaceTableOf(mapOf(31 to FIRST_SLOT))
        val done = AtomicBoolean(false)
        val ticker =
            Thread {
                while (!done.get()) frames.resendDue(routed)
            }
        sink.resender = ticker
        ticker.start()
        val event = SteppedTouchpadEvent()
        try {
            while (event.step < RACED_EVENTS && torn.get() == null) {
                event.step++
                frames.onCaptured(FIRST_SLOT, event, NO_POINTER_LIFTING, buttonPressed = event.step % 2 == 0L, eventTimeMs = event.step)
            }
        } finally {
            done.set(true)
            ticker.join()
        }

        assertEquals(null, torn.get())
        assertTrue("the ticks resent ${resent.get()} frames", resent.get() > 0)
    }

    private companion object {
        const val UNREACHABLE_SLOT = "9"
        const val LIFT_TIME_MS = 1_000_000L
        const val WARMUP_CYCLES = 20
        const val MEASURED_RUNS = 3
        const val RACED_EVENTS = 2_000_000L

        // Half the smallest object: one allocation per event or tick costs 16 bytes or more each
        // time, while the JIT's one-off warm-up allocations stay flat.
        const val BYTES_PER_STEP_BOUND = 8
    }
}

// Checks that every frame it is handed names one captured event in every field, and counts the
// ones the [resender] thread sent: those are the frames read across threads.
private class WholeFrameSink(
    private val torn: AtomicReference<String?>,
    private val resent: AtomicLong,
) : TelemetrySink {
    @Volatile var resender: Thread? = null

    override fun sendMotion(
        slotId: String,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    ) = Unit

    override fun sendBattery(
        slotId: String,
        level: Int,
        status: Int,
    ) = Unit

    override fun sendTouchpad(
        slotId: String,
        report: TouchpadReport,
    ) {
        if (Thread.currentThread() === resender) resent.incrementAndGet()
        val step = report.eventTimeMs
        val whole =
            report.finger0Active &&
                report.finger1Active &&
                report.buttonPressed == (step % 2 == 0L) &&
                report.finger0TrackingId == pointerIdAt(step) &&
                report.finger1TrackingId == pointerIdAt(step) &&
                report.finger0X == normalize(xAt(step), 0f, SURFACE_X_MAX) &&
                report.finger0Y == normalize(yAt(step), 0f, SURFACE_Y_MAX) &&
                report.finger1X == report.finger0X &&
                report.finger1Y == report.finger0Y
        if (!whole) torn.compareAndSet(null, "step $step sent as $report")
    }
}

// Each frame a sink was handed, as it was when handed over: a sender refills its report for the
// next frame, so only the copy keeps the frame.
private class CopyingSink : TelemetrySink {
    val frames = mutableListOf<TouchpadReport>()

    override fun sendMotion(
        slotId: String,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    ) = Unit

    override fun sendBattery(
        slotId: String,
        level: Int,
        status: Int,
    ) = Unit

    override fun sendTouchpad(
        slotId: String,
        report: TouchpadReport,
    ) {
        frames += report.copy()
    }
}
