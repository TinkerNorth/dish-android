// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

/**
 * One emulated pad's speaker endpoint: the slot to play for, addressed the way the stream arrives.
 *
 * MSG_SPEAKER_AUDIO names its controller by (session handle, controller index) and nothing else,
 * because that is all the host knows about us. Resolving that pair to a slot is the same job
 * [com.tinkernorth.dish.hotpath.input.FeedbackRouter] does for the lamp and the lightbar; the
 * difference is that this one is done ONCE per plan rather than once per frame, since 50 frames a
 * second per stream arrive on the native dispatch thread and that thread must not be spending them
 * walking connection maps.
 */
data class SpeakerTarget(
    val slotId: String,
    val sessionHandle: Int,
    val controllerIndex: Int,
    /** [PadAudioRoute.playbackDeviceId] for the slot: the pad's own endpoint, or the platform's choice. */
    val playbackDeviceId: Int,
)

/**
 * Everything the eligibility rule knows about one slot, flattened out of the capability model and
 * the connection hub so the rule itself stays pure.
 *
 * [speakerEnabled] is the composed answer, not the raw toggle: the whole path has to carry a
 * speaker (an audio-capable emulated type, on a host with controller audio on, behind an output
 * that can play it) AND the user has to have left it on. That is the same set the descriptor's
 * CAP_SPEAKER is projected from, so a slot that plays is always a slot the host was told to send
 * to.
 */
data class SpeakerSlotInput(
    val slotId: String,
    val sessionHandle: Int,
    val controllerIndex: Int,
    val streaming: Boolean,
    val speakerEnabled: Boolean,
    val playbackDeviceId: Int = NO_AUDIO_DEVICE,
)

/**
 * What the playback engine should be holding open right now, keyed by the address the frames
 * carry so the delivery path is one map lookup.
 */
data class SpeakerPlayoutPlan(
    val voices: Map<Long, SpeakerTarget>,
) {
    /** Whether anything is playing at all: what decides if the native dispatch thread is worth starting. */
    val playing: Boolean get() = voices.isNotEmpty()

    companion object {
        val IDLE = SpeakerPlayoutPlan(emptyMap())

        /**
         * (handle, controller index) as one key. Both are small non-negative ints by the time they
         * get here, so the pack is exact and the unpack is never needed.
         */
        fun routeKey(
            sessionHandle: Int,
            controllerIndex: Int,
        ): Long = (sessionHandle.toLong() shl Int.SIZE_BITS) or (controllerIndex.toLong() and INDEX_MASK)

        private const val INDEX_MASK = 0xFFFFFFFFL
    }
}

/**
 * The speaker eligibility rule, in one place and with nothing else in it.
 *
 * A slot plays only where ALL of these hold: it is bound to a live satellite session that has
 * given it a controller index, the whole capability path carries a speaker and the user left it
 * on, and the emulated pad exists on the host (the descriptor applied). Each can move
 * independently at runtime, and any one of them going false has to close the track, which is why
 * this is a rule and not three scattered guards.
 *
 * Nothing here mirrors the microphone's privacy invariant, because the directions are not
 * symmetric: this stream is one the user's own PC sends to the user's own phone. The gate exists
 * so a slot the user switched off does not open an audio output, and so a track is never held for
 * a pad that is gone.
 */
object SpeakerPlayoutPolicy {
    fun plan(slots: Collection<SpeakerSlotInput>): SpeakerPlayoutPlan {
        val voices = LinkedHashMap<Long, SpeakerTarget>()
        for (slot in slots) {
            if (!slot.streaming || !slot.speakerEnabled) continue
            if (slot.sessionHandle < 0 || slot.controllerIndex < 0) continue
            voices[SpeakerPlayoutPlan.routeKey(slot.sessionHandle, slot.controllerIndex)] =
                SpeakerTarget(
                    slotId = slot.slotId,
                    sessionHandle = slot.sessionHandle,
                    controllerIndex = slot.controllerIndex,
                    playbackDeviceId = slot.playbackDeviceId,
                )
        }
        return SpeakerPlayoutPlan(voices)
    }
}

/**
 * How much silence to slip in front of a window to rebuild the anti-underrun cushion.
 *
 * The satellite sends nothing for a digitally silent window, so a live stream goes quiet for
 * seconds at a time and the track drains. Resuming into a drained track leaves no cushion at all,
 * which is the condition the two-window start threshold exists to prevent.
 *
 * Silence rather than a pause-and-re-prime: withholding windows until the threshold is met again
 * would strand a sound shorter than the cushion, leaving a lone 20 ms blip unplayed until the next
 * one arrived. Writing silence delays the resumed audio by the same 40 ms and can never swallow it.
 *
 * The signal is the track's own underrun counter, which keeps wrapping frame arithmetic out of the
 * one path where a bug is audible.
 */
object SpeakerCushionPolicy {
    /**
     * Samples of silence to write before the next window, or 0 to write it straight through.
     *
     * [lastSeenUnderruns] is what this session observed the last time it refilled. A counter that
     * has not moved means the track kept up; one that went backwards means it was reset under us
     * (a flush, or a new track on the same session), which is not an underrun to compensate for.
     */
    fun refillSamples(
        playing: Boolean,
        underruns: Int,
        lastSeenUnderruns: Int,
        cushionSamples: Int,
    ): Int {
        // Not playing yet: the start threshold owns the cushion until it does.
        if (!playing) return 0
        if (cushionSamples <= 0) return 0
        return if (underruns > lastSeenUnderruns) cushionSamples else 0
    }
}
