// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import android.os.Process
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** What the microphone is doing, for the surfaces that report it and for the tests that pin it. */
enum class MicCaptureState {
    /** Nothing eligible, or everything eligible is muted. No recorder is open. */
    Idle,

    /** A recorder is open and its windows are going out to at least one slot. */
    Capturing,

    /**
     * The device would not hand over a microphone (refused, or the recorder died mid-stream). The
     * next time a slot arms one from scratch is the next attempt; a busy microphone is usually
     * another app's, and retrying in a tight loop would neither free it nor tell anyone.
     */
    Unavailable,
}

/** Runs the capture body off the caller's thread. Swapped in tests; the real one owns a thread. */
interface MicCaptureLoop {
    fun start(body: () -> Unit)

    /** Wait (briefly) for a previously started body to return. No-op when none is running. */
    fun join()
}

/** One loop per route, so the engine can hold several recorders at once. */
fun interface MicCaptureLoopFactory {
    fun create(preferredDeviceId: Int): MicCaptureLoop
}

/**
 * A dedicated thread at URGENT_AUDIO, matching the resend loop in
 * [com.tinkernorth.dish.ui.main.BaseInputOverlayActivity] and the native input threads
 * (`thread_priority.h`): a 20 ms window that misses its slot is a gap in somebody's voice, and the
 * default pool is shared with rendering and GC.
 */
internal class ThreadMicCaptureLoop(
    private val name: String,
) : MicCaptureLoop {
    private var thread: Thread? = null

    override fun start(body: () -> Unit) {
        thread =
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                body()
            }, name).also { it.start() }
    }

    override fun join() {
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    companion object {
        /** Named for the endpoint it captures from, so a stuck route is obvious in a thread dump. */
        fun nameFor(preferredDeviceId: Int): String =
            if (preferredDeviceId == NO_AUDIO_DEVICE) "dish-mic" else "dish-mic-$preferredDeviceId"

        // Generous next to one blocking 20 ms read; it exists so a wedged recorder cannot pin
        // whichever thread is starting the next capture.
        private const val JOIN_TIMEOUT_MS = 500L
    }
}

/**
 * The microphone capture pipeline: AudioRecord in, one 20 ms window at a time, out as
 * MSG_MIC_AUDIO to every slot whose emulated pad currently owns a microphone endpoint.
 *
 * THE PRIVACY INVARIANT. Muted (or the toggle off, or the permission gone, or nothing streaming)
 * means ZERO MSG_MIC_AUDIO packets leave the device. It is enforced by not capturing and not
 * sending, never by sending silence, and it is enforced twice on purpose:
 *
 *  - [apply] publishes the eligible target set BEFORE it starts anything and clears it BEFORE it
 *    stops anything, so the ineligible state is always the one in effect during the transition.
 *  - [Recorder.deliver] re-reads that set for every window, after the window was recorded rather
 *    than before. A mute that lands mid-window drops that window instead of shipping it, which is
 *    what bounds mute latency to one 20 ms frame and makes "muted" mean silence on the wire and
 *    not just silence in the audio.
 *
 * Lifecycle is process-STARTED, like every other streaming source here: leaving the app tears down
 * the foreground service and with it the session, so a capture that outlived the foreground would
 * have nowhere to send.
 *
 * ONE RECORDER PER ROUTE, not per slot. The phone has one microphone, so two emulated pads asking
 * for it are asking for the same sound and share a recorder. A Direct-claimed pad with its own
 * microphone endpoint is a different source, and an AudioRecord's preferred device is fixed when it
 * is built, so that slot gets its own recorder pointed at the pad's own headset. The route table
 * rides the upstream for the same reason: a pad's endpoint appearing or vanishing moves a slot
 * between recorders without changing the plan at all.
 */
@Singleton
class MicEngine
    internal constructor(
        private val plans: MicCaptureComposer,
        private val satellite: SatelliteConnectionManager,
        private val source: MicCaptureSource,
        private val loops: MicCaptureLoopFactory,
        private val routing: SlotAudioRoutes,
        scope: CoroutineScope,
    ) : AbstractController<MicCapturePlan>(scope) {
        @Inject
        constructor(
            plans: MicCaptureComposer,
            satellite: SatelliteConnectionManager,
            source: AudioRecordMicSource,
            routing: PadAudioRouting,
            scope: CoroutineScope,
        ) : this(
            plans,
            satellite,
            source,
            MicCaptureLoopFactory { ThreadMicCaptureLoop(ThreadMicCaptureLoop.nameFor(it)) },
            routing,
            scope,
        )

        private val _state = MutableStateFlow(MicCaptureState.Idle)
        val state: StateFlow<MicCaptureState> = _state.asStateFlow()

        /**
         * The eligible targets, grouped by the endpoint their windows come from. Read by every
         * capture thread for every window; written by the collector as one whole map.
         */
        @Volatile private var delivering: Map<Int, Set<MicCaptureTarget>> = emptyMap()

        /**
         * One recorder per route, kept for the life of the process rather than dropped when its
         * route disarms: a re-arm has to JOIN the previous capture on that route before opening
         * another, and one microphone at a time is a property of the start. Bounded by the number
         * of distinct capture endpoints ever armed, which is the phone's own plus one per pad.
         * Immutable snapshots, since a capture thread reads it to report its own failure.
         */
        @Volatile private var recorders: Map<Int, Recorder> = emptyMap()

        /** Which routes are meant to be capturing, so a retired recorder is not counted as one. */
        @Volatile private var armedRoutes: Set<Int> = emptySet()

        /**
         * True when no capture body is executing: every recorder has closed its microphone and no
         * window is left between one and the sender.
         *
         * Deliberately a different question from [state] being [MicCaptureState.Idle]. Idle says
         * the engine was TOLD to stop, which the plan decides and which happens synchronously in
         * [apply]; this says it HAS, which is what the privacy claim is about and what a caller
         * must be able to wait for rather than sleep through. The two differ for at most one
         * blocking read, and a window recorded during that gap is dropped rather than sent, so the
         * difference is never packets.
         */
        val quiescent: Boolean get() = recorders.values.none { it.executing }

        override fun upstream(): Flow<MicCapturePlan> =
            // Deliberately no distinctUntilChanged: an unchanged plan over a changed route table is
            // a real change of which recorder a slot belongs to, and [apply] is a reconcile that
            // costs nothing when nothing moved.
            combine(plans.state, routing.changes) { plan, _ -> plan }

        // Public because it IS the engine: the eligibility decision arrives here and nothing else
        // starts or stops a microphone.
        public override fun apply(value: MicCapturePlan) {
            // Order matters both ways round, and one assignment gets both. Widening before starting
            // means the first window already has somewhere to go; narrowing before stopping means a
            // window recorded a moment ago is dropped rather than sent to a slot that just muted.
            val byRoute =
                value.delivering
                    .groupBy { routing.forSlot(it.slotId).captureDeviceId }
                    .mapValues { (_, targets) -> targets.toSet() }
            delivering = byRoute
            reconcile(byRoute.keys)
        }

        override fun onStop(owner: LifecycleOwner) {
            super.onStop(owner)
            // Inputs keep moving while the actuator is stopped (AbstractController's contract), so
            // the microphone must not: a stopped engine is a stopped recorder.
            delivering = emptyMap()
            reconcile(emptySet())
        }

        /** Capture on exactly the wanted routes, starting and stopping only what moved. */
        private fun reconcile(wanted: Set<Int>) {
            armedRoutes = wanted
            val current = recorders
            for ((route, recorder) in current) if (route !in wanted) recorder.stop()
            val missing = wanted.filter { it !in current }
            if (missing.isNotEmpty()) {
                recorders = current + missing.associateWith { Recorder(it) }
            }
            for (route in wanted) recorders[route]?.ensureStarted()
            refreshState()
        }

        /**
         * The aggregate of what the armed recorders are doing. Read from the volatile snapshots
         * rather than accumulated, so a capture thread reporting its own failure and the collector
         * reconciling cannot leave a state behind that no recorder is in. A recorder whose route
         * disarmed is not counted: it is retained only to be joined if that route comes back.
         */
        private fun refreshState() {
            val armed = armedRoutes
            val live = recorders.filterKeys { it in armed }.values
            _state.value =
                when {
                    live.any { it.capturing } -> MicCaptureState.Capturing
                    live.any { it.failed } -> MicCaptureState.Unavailable
                    else -> MicCaptureState.Idle
                }
        }

        /** One microphone, feeding the slots that share its endpoint. */
        private inner class Recorder(
            private val preferredDeviceId: Int,
        ) {
            private val loop = loops.create(preferredDeviceId)

            /** The capture thread's continue condition, and the second half of the delivery gate. */
            @Volatile private var running = false

            // Whether this device already refused, or died, for the current arming. Written by the
            // capture thread and read by the collector, hence volatile.
            @Volatile private var broken = false

            // Whether a capture is meant to exist. Collector-thread only, deliberately: the capture
            // thread never clears it, so a failed loop cannot race a start into opening two recorders.
            private var started = false

            // Whether a body is executing right now, as opposed to being told to keep going. Set
            // before the body is handed to the loop and cleared only when it returns, so this is
            // never falsely quiet: it errs towards "still busy", which is the safe direction for
            // anything waiting on it.
            @Volatile private var bodyRunning = false

            val capturing: Boolean get() = running

            val failed: Boolean get() = broken

            val executing: Boolean get() = bodyRunning

            /**
             * Start, unless one is already meant to exist. A refusal leaves [started] set, which is
             * what stops a re-applied plan from hammering a device that already said no.
             */
            fun ensureStarted() {
                if (started) return
                // A previous capture may still be unwinding its last blocking read. Wait for it
                // HERE, not in stop: stopping happens on the main thread at background time, and one
                // recorder at a time is a property of the start, not of the stop.
                loop.join()
                // A refusal was for one arming only, and this is where it is forgotten: a busy
                // microphone is usually another app's, and that app may well have finished. Cleared
                // here rather than in stop because the join above means the body that recorded it
                // has returned, so this cannot race the thread that sets it.
                broken = false
                started = true
                running = true
                bodyRunning = true
                loop.start(::captureLoop)
            }

            fun stop() {
                started = false
                running = false
            }

            private fun captureLoop() {
                // The outer finally is what [executing] promises: whichever way the body leaves,
                // the recorder is closed and nothing more will be handed to the sender.
                try {
                    runCapture()
                } finally {
                    bodyRunning = false
                }
            }

            private fun runCapture() {
                val session = source.open(FRAME_SAMPLES, preferredDeviceId)
                if (session == null) {
                    Log.w(TAG, "no microphone at endpoint $preferredDeviceId, capture stays off until a slot re-arms it")
                    markBroken()
                    return
                }
                if (!session.voiceProcessed) {
                    Log.i(TAG, "capturing without platform echo cancellation (fallback source)")
                }
                var clean = false
                try {
                    val window = ShortArray(FRAME_SAMPLES)
                    while (running) {
                        // A short read is a dead recorder, not a short packet: never send a partial
                        // window, the far end cannot place one in its timeline.
                        if (session.read(window) != window.size) break
                        deliver(window)
                    }
                    clean = !running
                } finally {
                    session.close()
                }
                if (!clean) markBroken()
            }

            private fun deliver(window: ShortArray) {
                // Re-read, do not cache: this is the check that makes mute mean zero packets rather
                // than "zero packets soon".
                if (!running) return
                for (target in delivering[preferredDeviceId].orEmpty()) {
                    satellite.get(target.connectionId)?.sendMicFrame(target.slotId, window)
                }
            }

            private fun markBroken() {
                running = false
                broken = true
                refreshState()
            }
        }

        companion object {
            private const val TAG = "MicEngine"

            /** Fixed by the wire (contract §Controller audio), not by the device. */
            const val SAMPLE_RATE = 48_000

            /** 20 ms at 48 kHz. SatelliteNative.sendMicFrame refuses any other window. */
            const val FRAME_SAMPLES = 960
        }
    }
