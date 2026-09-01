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

/**
 * A dedicated thread at URGENT_AUDIO, matching the resend loop in
 * [com.tinkernorth.dish.ui.main.BaseInputOverlayActivity] and the native input threads
 * (`thread_priority.h`): a 20 ms window that misses its slot is a gap in somebody's voice, and the
 * default pool is shared with rendering and GC.
 */
internal class ThreadMicCaptureLoop : MicCaptureLoop {
    private var thread: Thread? = null

    override fun start(body: () -> Unit) {
        thread =
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                body()
            }, THREAD_NAME).also { it.start() }
    }

    override fun join() {
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    private companion object {
        const val THREAD_NAME = "dish-mic"

        // Generous next to one blocking 20 ms read; it exists so a wedged recorder cannot pin
        // whichever thread is starting the next capture.
        const val JOIN_TIMEOUT_MS = 500L
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
 *  - [deliver] re-reads that set for every window, after the window was recorded rather than
 *    before. A mute that lands mid-window drops that window instead of shipping it, which is what
 *    bounds mute latency to one 20 ms frame and makes "muted" mean silence on the wire and not
 *    just silence in the audio.
 *
 * Lifecycle is process-STARTED, like every other streaming source here: leaving the app tears down
 * the foreground service and with it the session, so a capture that outlived the foreground would
 * have nowhere to send.
 *
 * ONE recorder, fanned out to every eligible slot, because the phone has one microphone and two
 * emulated pads asking for it are asking for the same sound. The playback wave's per-pad routing
 * (AudioRecord.setPreferredDevice against a Direct-claimed pad's own headset) is what will need a
 * capture per route rather than per device.
 */
@Singleton
class MicEngine
    internal constructor(
        private val plans: MicCaptureComposer,
        private val satellite: SatelliteConnectionManager,
        private val source: MicCaptureSource,
        private val loop: MicCaptureLoop,
        scope: CoroutineScope,
    ) : AbstractController<MicCapturePlan>(scope) {
        @Inject
        constructor(
            plans: MicCaptureComposer,
            satellite: SatelliteConnectionManager,
            source: AudioRecordMicSource,
            scope: CoroutineScope,
        ) : this(plans, satellite, source, ThreadMicCaptureLoop(), scope)

        private val _state = MutableStateFlow(MicCaptureState.Idle)
        val state: StateFlow<MicCaptureState> = _state.asStateFlow()

        /** Read by the capture thread for every window; written by the collector. */
        @Volatile private var delivering: Set<MicCaptureTarget> = emptySet()

        /** The capture thread's continue condition, and the second half of the delivery gate. */
        @Volatile private var running = false

        // Whether a capture is meant to exist. Collector-thread only, deliberately: the capture
        // thread never clears it, so a failed loop cannot race a start into opening two recorders.
        private var capturing = false

        // Whether this device already refused to provide a microphone for the current arming.
        // Written by the capture thread on failure and read by the collector, hence volatile.
        @Volatile private var unavailable = false

        override fun upstream(): Flow<MicCapturePlan> = plans.state

        // Public because it IS the engine: the eligibility decision arrives here and nothing else
        // starts or stops a microphone.
        public override fun apply(value: MicCapturePlan) {
            // Order matters both ways round. Widening the set before starting means the first
            // window already has somewhere to go; narrowing it before stopping means a window
            // recorded a moment ago is dropped rather than sent to a slot that just muted.
            delivering = value.delivering
            if (value.capturing) startCapture() else stopCapture()
        }

        override fun onStop(owner: LifecycleOwner) {
            super.onStop(owner)
            // Inputs keep moving while the actuator is stopped (AbstractController's contract), so
            // the microphone must not: a stopped engine is a stopped recorder.
            delivering = emptySet()
            stopCapture()
        }

        private fun startCapture() {
            if (capturing || unavailable) return
            // A previous capture may still be unwinding its last blocking read. Wait for it HERE,
            // not in stopCapture: stopping happens on the main thread at background time, and one
            // recorder at a time is a property of the start, not of the stop.
            loop.join()
            capturing = true
            running = true
            _state.value = MicCaptureState.Capturing
            loop.start(::captureLoop)
        }

        private fun stopCapture() {
            // A refusal was for one arming only; a fresh one is a fresh attempt, since a busy
            // microphone is usually another app's and that app may well have finished.
            unavailable = false
            if (!capturing) {
                if (_state.value != MicCaptureState.Unavailable) _state.value = MicCaptureState.Idle
                return
            }
            capturing = false
            running = false
            _state.value = MicCaptureState.Idle
        }

        private fun captureLoop() {
            val session = source.open(FRAME_SAMPLES)
            if (session == null) {
                Log.w(TAG, "no microphone available, capture stays off until a slot re-arms it")
                markUnavailable()
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
            if (!clean) markUnavailable()
        }

        private fun deliver(window: ShortArray) {
            // Re-read, do not cache: this is the check that makes mute mean zero packets rather
            // than "zero packets soon".
            if (!running) return
            for (target in delivering) {
                satellite.get(target.connectionId)?.sendMicFrame(target.slotId, window)
            }
        }

        private fun markUnavailable() {
            running = false
            unavailable = true
            _state.value = MicCaptureState.Unavailable
        }

        companion object {
            private const val TAG = "MicEngine"

            /** Fixed by the wire (contract §Controller audio), not by the device. */
            const val SAMPLE_RATE = 48_000

            /** 20 ms at 48 kHz. SatelliteNative.sendMicFrame refuses any other window. */
            const val FRAME_SAMPLES = 960
        }
    }
