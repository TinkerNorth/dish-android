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
    /** Which of the endpoint's lane pairs this voice writes. */
    val lane: PlayoutLane = PlayoutLane.SPEAKER,
    /**
     * The width to open the endpoint at: the wire's stereo, or the pad's own 4 (the haptic lane
     * needs 4; the speaker lane opens at 4 on the same pad so its stereo never lands on the
     * actuators).
     */
    val deviceChannels: Int = PlayoutLane.STEREO_CHANNELS,
)

/**
 * The two lane pairs of a DualSense's own render endpoint, speaker first, in the pad's own
 * channel order. Each is its own voice on the same endpoint rather than a mix into one track:
 * the two streams are independent on the wire (own seq, own silence suppression), so pairing
 * their windows would need a clock the push model has not got; the platform mixes the tracks.
 */
enum class PlayoutLane(
    val pairOffset: Int,
) {
    SPEAKER(0),
    HAPTICS(2),
    ;

    companion object {
        const val STEREO_CHANNELS = 2
        const val QUAD_CHANNELS = 4
    }
}

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
    /** The composed [com.tinkernorth.dish.core.model.Feature.HAPTIC_AUDIO] answer, like [speakerEnabled]. */
    val hapticEnabled: Boolean = false,
    /** [PadAudioRoute.playbackChannels]: 0 = unknown, which opens as stereo. */
    val playbackChannels: Int = 0,
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
         * (handle, controller index, lane) as one key. Handle and index are small non-negative
         * ints by the time they get here, so the pack is exact and the unpack is never needed.
         */
        fun routeKey(
            sessionHandle: Int,
            controllerIndex: Int,
            lane: PlayoutLane = PlayoutLane.SPEAKER,
        ): Long =
            (sessionHandle.toLong() shl HANDLE_SHIFT) or
                (lane.ordinal.toLong() shl Int.SIZE_BITS) or
                (controllerIndex.toLong() and INDEX_MASK)

        private const val INDEX_MASK = 0xFFFFFFFFL
        private const val HANDLE_SHIFT = Int.SIZE_BITS + 1
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
fun speakerPlayoutPlanFor(slots: Collection<SpeakerSlotInput>): SpeakerPlayoutPlan {
    val voices = LinkedHashMap<Long, SpeakerTarget>()
    for (slot in slots) {
        if (!slot.streaming) continue
        if (slot.sessionHandle < 0 || slot.controllerIndex < 0) continue
        // The endpoint's own width when the platform reported one, so a 4-channel pad
        // gets its stereo on the speaker pair and not spread across the actuators.
        val channels =
            if (slot.playbackChannels > 0) slot.playbackChannels else PlayoutLane.STEREO_CHANNELS
        if (slot.speakerEnabled) {
            voices[SpeakerPlayoutPlan.routeKey(slot.sessionHandle, slot.controllerIndex, PlayoutLane.SPEAKER)] =
                SpeakerTarget(
                    slotId = slot.slotId,
                    sessionHandle = slot.sessionHandle,
                    controllerIndex = slot.controllerIndex,
                    playbackDeviceId = slot.playbackDeviceId,
                    lane = PlayoutLane.SPEAKER,
                    deviceChannels = channels,
                )
        }
        // The haptic lane needs its pair to exist at the offset it writes.
        if (slot.hapticEnabled && channels >= PlayoutLane.QUAD_CHANNELS) {
            voices[SpeakerPlayoutPlan.routeKey(slot.sessionHandle, slot.controllerIndex, PlayoutLane.HAPTICS)] =
                SpeakerTarget(
                    slotId = slot.slotId,
                    sessionHandle = slot.sessionHandle,
                    controllerIndex = slot.controllerIndex,
                    playbackDeviceId = slot.playbackDeviceId,
                    lane = PlayoutLane.HAPTICS,
                    deviceChannels = channels,
                )
        }
    }
    return SpeakerPlayoutPlan(voices)
}
// How much silence to slip in front of a window to rebuild the anti-underrun cushion.
// The satellite sends nothing for a digitally silent window, so a live stream goes quiet for
// seconds at a time and the track drains. Resuming into a drained track leaves no cushion at all,
// which is the condition the two-window start threshold exists to prevent.
// Silence rather than a pause-and-re-prime: withholding windows until the threshold is met again
// would strand a sound shorter than the cushion, leaving a lone 20 ms blip unplayed until the next
// one arrived. Writing silence delays the resumed audio by the same 40 ms and can never swallow it.
// The signal is the track's own underrun counter, which keeps wrapping frame arithmetic out of the
// one path where a bug is audible.

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
