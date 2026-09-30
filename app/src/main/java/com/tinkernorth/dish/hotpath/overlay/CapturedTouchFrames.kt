// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.overlay

import com.tinkernorth.dish.hotpath.input.CapturedSurfaceTable
import com.tinkernorth.dish.hotpath.input.CapturedTouchpadEvent
import com.tinkernorth.dish.hotpath.input.PadTouchFrame
import com.tinkernorth.dish.hotpath.input.frame
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.ui.common.ResendPacer
import com.tinkernorth.dish.ui.common.TouchpadReportBuffer
import java.util.concurrent.locks.StampedLock

/** Where a slot's frames go: its sink while the slot is reachable, none while it is not. */
internal fun interface TouchSinks {
    fun sinkFor(slotId: String): TelemetrySink?
}

/**
 * The frames of every captured pad surface, and the resend that heals a lost one. The main thread
 * hands each captured event and each lift here and sends it at once; the resend thread ticks, and
 * re-sends a changed frame EDGE_BURST_RESENDS ticks in a row, then on the slow keepalive, so a
 * lost finger-up heals at the next tick; the receiver drops a duplicate by its equal event time.
 *
 * Neither thread builds anything per event or per tick: each maps or copies into a frame of its
 * own, and a slot's latest frame lives in its [CapturedTouchSlot], which the resend thread reads
 * whole. A slot is made the first time its surface sends and is kept for the capture's life (a
 * forgotten one only stops being held), so the slots never outnumber the pads the screen routed.
 */
internal class CapturedTouchFrames(
    private val sinks: TouchSinks,
) {
    // Written on the main thread only, when a slot sends for the first time; read on both.
    @Volatile private var slots: Array<CapturedTouchSlot> = emptyArray()

    // Each thread's own frame and wire frame: the main thread's captured events and lifts, and
    // the resend thread's ticks.
    private val mainFrame = PadTouchFrame()
    private val mainReport = TouchpadReportBuffer()
    private val resendFrame = PadTouchFrame()
    private val resendReport = TouchpadReportBuffer()

    /** Main thread: one captured event for [slotId], kept for the resend and sent now. */
    fun onCaptured(
        slotId: String,
        event: CapturedTouchpadEvent,
        liftingIndex: Int,
        buttonPressed: Boolean,
        eventTimeMs: Long,
    ) {
        frame(event, liftingIndex, buttonPressed, eventTimeMs, into = mainFrame)
        slotFor(slotId).publish(mainFrame)
        sinks.sinkFor(slotId)?.let { send(it, slotId, mainFrame, mainReport) }
    }

    /** Main thread: lift every held slot whose last frame still holds a finger or the click. */
    fun liftAll(nowMs: Long) {
        for (slot in slots) {
            if (!slot.liftInto(mainFrame, nowMs)) continue
            sinks.sinkFor(slot.slotId)?.let { send(it, slot.slotId, mainFrame, mainReport) }
        }
    }

    /**
     * Resend thread: one tick over every slot holding a frame. A slot [routed] no longer names is
     * forgotten once its burst is out. True while any slot still holds a frame.
     */
    fun resendDue(routed: CapturedSurfaceTable): Boolean {
        var holdsAFrame = false
        for (slot in slots) {
            if (resendSlot(slot, routed)) holdsAFrame = true
        }
        return holdsAFrame
    }

    // One slot's tick; true while the slot still holds a frame after it.
    private fun resendSlot(
        slot: CapturedTouchSlot,
        routed: CapturedSurfaceTable,
    ): Boolean {
        val written = slot.readInto(resendFrame)
        if (!slot.isHeldAt(written)) return false
        val sink = sinks.sinkFor(slot.slotId)
        val due = slot.pacer.resendDue(slot.tookChange(resendFrame))
        val step = resendStepFor(due, hasSink = sink != null, routed = routed.isRouted(slot.slotId))
        // By identity, not a when over the step: that reads its ordinal(), which the allocation
        // tests measure as MockK rewrites it, allocating on every call (ThreadAllocation.kt).
        when {
            step === ResendStep.SEND -> if (sink != null) send(sink, slot.slotId, resendFrame, resendReport)
            step === ResendStep.FORGET -> slot.forget(written)
        }
        return step !== ResendStep.FORGET
    }

    // Main thread: the slot's own, made the first time it sends.
    private fun slotFor(slotId: String): CapturedTouchSlot {
        for (slot in slots) {
            if (slot.slotId == slotId) return slot
        }
        val made = CapturedTouchSlot(slotId)
        slots += made
        return made
    }

    private fun send(
        sink: TelemetrySink,
        slotId: String,
        frame: PadTouchFrame,
        buffer: TouchpadReportBuffer,
    ) {
        sink.sendTouchpad(slotId, buffer.reportOf(frame))
    }
}

/**
 * One slot's latest captured frame, which the main thread writes and the resend thread reads
 * whole. The writer holds the stamped lock's write lock while it copies the frame in; the reader
 * copies it out optimistically and, if a write crossed the copy, again under the read lock. The
 * lock builds a wait node only when the two contend; an uncontended write or read builds nothing.
 *
 * [writes] counts the frames written, so the resend thread can forget the slot as of the write it
 * read: a frame the main thread writes after that holds the slot again, with no lock between the
 * forgetting and the write.
 */
internal class CapturedTouchSlot(
    val slotId: String,
) {
    private val lock = StampedLock()
    private val latest = PadTouchFrame()

    // [latest] and [writes] are written by the main thread under the write lock, and read by the
    // resend thread under the read lock or an optimistic stamp, and by the main thread, their only
    // writer, freely.
    private var writes = 0L

    // The write count the resend thread last forgot the slot at; none written is none held.
    @Volatile private var forgottenAt = 0L

    // Resend-thread-only: the slot's pacing, and the frame it last took as a change.
    val pacer = ResendPacer()
    private val lastTaken = PadTouchFrame()
    private var hasTaken = false

    /** Main thread: [frame] is the slot's latest. */
    fun publish(frame: PadTouchFrame) {
        val stamp = lock.writeLock()
        try {
            latest.copyFrom(frame)
            writes++
        } finally {
            lock.unlockWrite(stamp)
        }
    }

    /**
     * Main thread: when the slot is held and its latest frame still holds a finger or the click,
     * fills [out] with it lifted at [nowMs], publishes that and answers true.
     */
    fun liftInto(
        out: PadTouchFrame,
        nowMs: Long,
    ): Boolean {
        val liftable = isHeldAt(writes) && latest.holdsATouch()
        if (liftable) {
            out.copyFrom(latest)
            out.lift(nowMs)
            publish(out)
        }
        return liftable
    }

    /** Resend thread: fills [out] with the latest frame, whole, and answers the write it was. */
    fun readInto(out: PadTouchFrame): Long {
        val optimistic = lock.tryOptimisticRead()
        out.copyFrom(latest)
        val written = writes
        if (lock.validate(optimistic)) return written
        val stamp = lock.readLock()
        try {
            out.copyFrom(latest)
            return writes
        } finally {
            lock.unlockRead(stamp)
        }
    }

    /** Whether the slot holds a frame as of write [written]: one was written and not forgotten. */
    fun isHeldAt(written: Long): Boolean = written != forgottenAt

    /** Resend thread: whether [frame] differs from the last one taken, taking it if so. */
    fun tookChange(frame: PadTouchFrame): Boolean {
        if (hasTaken && frame == lastTaken) return false
        lastTaken.copyFrom(frame)
        hasTaken = true
        return true
    }

    /** Resend thread: the slot holds nothing as of write [written]; its next frame is a change. */
    fun forget(written: Long) {
        forgottenAt = written
        hasTaken = false
    }
}
