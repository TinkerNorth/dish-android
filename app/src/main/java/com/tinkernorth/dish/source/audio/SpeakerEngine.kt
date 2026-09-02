// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.composer.SpeakerPlayoutComposer
import com.tinkernorth.dish.hotpath.audio.SpeakerAudioBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What playback is doing, for the surfaces that report it and for the tests that pin it. */
enum class SpeakerPlayoutState {
    /** Nothing eligible. No track is open and the native dispatch thread has no sink. */
    Idle,

    /** At least one track is open and taking frames. */
    Playing,

    /**
     * Every eligible slot was refused an output (no audio HAL, the format declined, another app
     * holding something exclusive). The next plan change is the next attempt; retrying in a tight
     * loop would neither free an output nor tell anyone.
     */
    Unavailable,
}

/**
 * Where decoded speaker frames come from. The real one is the native bridge; abstracted only so
 * the engine can be tested without loading the shared library.
 */
interface SpeakerFrameSource {
    fun install(sink: SpeakerAudioBridge.Sink)

    fun uninstall()
}

/** Installs into the native dispatch path, which also starts the thread behind it. */
object NativeSpeakerFrameSource : SpeakerFrameSource {
    override fun install(sink: SpeakerAudioBridge.Sink) = SpeakerAudioBridge.install(sink)

    override fun uninstall() = SpeakerAudioBridge.uninstall()
}

/**
 * The playback pipeline: one 20 ms window of decoded stereo PCM at a time, out of the native
 * reorder/decode thread and into one [android.media.AudioTrack] per playing slot.
 *
 * The frames arrive already in stream order, already whole, and already concealed where a packet
 * never came, so there is nothing left to decide per frame except which track it belongs to. That
 * is why the plan is keyed by (session handle, controller index): the delivery path is one hash
 * lookup on a volatile snapshot, with no lock, no allocation and no connection map walk, on a
 * thread that is shared with every other stream.
 *
 * ONE track per slot rather than one mixed output, because they are different endpoints: two bound
 * pads are two emulated speakers, and one of them may be routed to a physical pad's own headset
 * while the other plays out the phone.
 *
 * Lifecycle is process-STARTED, like every other streaming source here: leaving the app tears down
 * the foreground service and with it the session, so a track that outlived the foreground would
 * have nothing to play. The native sink is installed only while something is playing, so a session
 * with controller sound switched off never starts the dispatch thread at all.
 */
@Singleton
class SpeakerEngine
    internal constructor(
        private val plans: SpeakerPlayoutComposer,
        private val sink: SpeakerPlayoutSink,
        private val frames: SpeakerFrameSource,
        scope: CoroutineScope,
    ) : AbstractController<SpeakerPlayoutPlan>(scope),
        SpeakerAudioBridge.Sink {
        @Inject
        constructor(
            plans: SpeakerPlayoutComposer,
            sink: AudioTrackSpeakerSink,
            scope: CoroutineScope,
        ) : this(plans, sink, NativeSpeakerFrameSource, scope)

        private val _state = MutableStateFlow(SpeakerPlayoutState.Idle)
        val state: StateFlow<SpeakerPlayoutState> = _state.asStateFlow()

        /**
         * Samples the tracks would not take. A live stream whose sink is full is one running ahead
         * of playback, and dropping the newest window is what pulls it back; the count is here so
         * that stays a measurable fact rather than a silent one.
         */
        val droppedSamples = AtomicLong()

        private class Voice(
            val target: SpeakerTarget,
            val session: SpeakerPlayoutSession,
        )

        /** Read by the native dispatch thread for every frame; replaced wholesale by the collector. */
        @Volatile private var voices: Map<Long, Voice> = emptyMap()

        // Whether the native sink is ours. Collector-thread only.
        private var installed = false

        override fun upstream(): Flow<SpeakerPlayoutPlan> = plans.state

        // Public because it IS the engine: the eligibility decision arrives here and nothing else
        // opens or closes an output.
        public override fun apply(value: SpeakerPlayoutPlan) {
            reconcile(value.voices)
        }

        override fun onStop(owner: LifecycleOwner) {
            super.onStop(owner)
            // Inputs keep moving while the actuator is stopped (AbstractController's contract), so
            // playback must not: a stopped engine is a closed track.
            reconcile(emptyMap())
        }

        /**
         * Bring the open tracks to exactly [desired].
         *
         * Order matters both ways round, the same way it does for capture: a track is published
         * before it can be written to and unpublished before it is closed, so the dispatch thread
         * never holds a reference to something being torn down. Closing still takes the session's
         * own lock, because a frame may already be inside a write.
         */
        private fun reconcile(desired: Map<Long, SpeakerTarget>) {
            val current = voices
            // A route change (the pad's endpoint appeared, moved or went away) is a reopen: an
            // AudioTrack's preferred device is settled when it is built.
            val kept =
                current.filterKeys { key ->
                    desired[key]?.playbackDeviceId == current[key]?.target?.playbackDeviceId
                }
            val gone = current.filterKeys { it !in kept.keys }
            if (gone.isNotEmpty()) {
                voices = kept
                gone.values.forEach { it.session.close() }
            }

            val open = HashMap<Long, Voice>(desired.size)
            var refused = 0
            for ((key, target) in desired) {
                val existing = kept[key]
                if (existing != null) {
                    // Same endpoint, possibly a renamed slot: keep the track, take the new target.
                    open[key] = Voice(target, existing.session)
                    continue
                }
                val session = sink.open(FRAME_SAMPLES, target.playbackDeviceId)
                if (session == null) {
                    refused++
                    continue
                }
                open[key] = Voice(target, session)
            }
            voices = open
            if (open.isNotEmpty()) installSink() else uninstallSink()
            _state.value =
                when {
                    open.isNotEmpty() -> SpeakerPlayoutState.Playing
                    refused > 0 -> SpeakerPlayoutState.Unavailable
                    else -> SpeakerPlayoutState.Idle
                }
            if (refused > 0) Log.w(TAG, "$refused speaker slot(s) got no output from this device")
        }

        private fun installSink() {
            if (installed) return
            installed = true
            frames.install(this)
        }

        private fun uninstallSink() {
            if (!installed) return
            installed = false
            frames.uninstall()
        }

        /**
         * The native dispatch thread's one call. Everything here is bounded: a map read, a lookup,
         * and a write that cannot block. A frame for a slot that is not playing (the plan changed
         * while it was in flight, or the host is sending for a cap we withdrew) is dropped, which
         * is the same answer the reorder window gives a frame that arrived too late.
         *
         * [concealed] is not acted on: concealment already happened natively and produced these
         * very samples, so there is nothing left to do but play them. It rides the call because the
         * sink is the only place that could ever meter it.
         */
        override fun onSpeakerFrame(
            sessionHandle: Int,
            controllerIndex: Int,
            pcmStereo: ShortArray,
            concealed: Boolean,
        ) {
            val voice = voices[SpeakerPlayoutPlan.routeKey(sessionHandle, controllerIndex)] ?: return
            val written = voice.session.write(pcmStereo)
            if (written < pcmStereo.size) droppedSamples.addAndGet((pcmStereo.size - written).toLong())
        }

        companion object {
            private const val TAG = "SpeakerEngine"

            /** Fixed by the wire (contract §Controller audio), not by the device. */
            const val SAMPLE_RATE = 48_000

            /** 20 ms of interleaved stereo at 48 kHz: 960 frames, two samples each. */
            const val FRAME_SAMPLES = 1920
        }
    }
