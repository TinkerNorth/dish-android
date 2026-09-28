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
import java.util.concurrent.ConcurrentHashMap

/** Where a slot's frames go: its sink while the slot is reachable, none while it is not. */
internal fun interface TouchSinks {
    fun sinkFor(slotId: String): TelemetrySink?
}

/**
 * The frames of every captured pad surface, and the resend that heals a lost one. The main thread
 * hands each captured event and each lift here and sends it at once; the resend thread ticks, and
 * re-sends a changed frame EDGE_BURST_RESENDS ticks in a row, then on the slow keepalive, so a
 * lost finger-up heals at the next tick; the receiver drops a duplicate by its equal event time.
 */
internal class CapturedTouchFrames(
    private val sinks: TouchSinks,
) {
    // Last frame per slot: written on the main thread (the captured event), read on the resend
    // thread. Frames are immutable, so a reader never sees a torn one.
    private val lastFrame = ConcurrentHashMap<String, PadTouchFrame>()

    // Resend-thread-only.
    private val pacers = HashMap<String, ResendPacer>()
    private val lastResent = HashMap<String, PadTouchFrame>()

    // The wire frame, one per sending thread: the main thread's captured events and lifts, and
    // the resend thread's ticks.
    private val mainReport = TouchpadReportBuffer()
    private val resendReport = TouchpadReportBuffer()

    /** Main thread: one captured event for [slotId], kept for the resend and sent now. */
    fun onCaptured(
        slotId: String,
        event: CapturedTouchpadEvent,
        liftingIndex: Int,
        buttonPressed: Boolean,
        eventTimeMs: Long,
    ) {
        val frame = frame(event, liftingIndex, buttonPressed, eventTimeMs)
        lastFrame[slotId] = frame
        sinks.sinkFor(slotId)?.let { send(it, slotId, frame, mainReport) }
    }

    /** Main thread: lift every slot whose last frame still holds a finger or the click. */
    fun liftAll(nowMs: Long) {
        for ((slotId, lifted) in liftedFrames(lastFrame, nowMs)) {
            lastFrame[slotId] = lifted
            sinks.sinkFor(slotId)?.let { send(it, slotId, lifted, mainReport) }
        }
    }

    /**
     * Resend thread: one tick over every slot holding a frame. A slot [routed] no longer names is
     * forgotten once its burst is out. True while any slot still holds a frame.
     */
    fun resendDue(routed: CapturedSurfaceTable): Boolean {
        for ((slotId, frame) in lastFrame) {
            val sink = sinks.sinkFor(slotId)
            val changed = frame != lastResent[slotId]
            if (changed) lastResent[slotId] = frame
            val pacer = pacers.getOrPut(slotId) { ResendPacer() }
            val due = pacer.resendDue(changed)
            when (resendStepFor(due, hasSink = sink != null, routed = routed.isRouted(slotId))) {
                ResendStep.SEND -> if (sink != null) send(sink, slotId, frame, resendReport)
                ResendStep.FORGET -> forgetSlot(slotId)
                ResendStep.KEEP -> Unit
            }
        }
        return lastFrame.isNotEmpty()
    }

    private fun forgetSlot(slotId: String) {
        lastFrame.remove(slotId)
        lastResent.remove(slotId)
        pacers.remove(slotId)
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
