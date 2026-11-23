// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

const val FRAMES = 40
const val FRAME_MS = 20L

private const val FIRST_HZ = 660.0
private const val SECOND_HZ = 880.0
private const val AMPLITUDE = 0.35
private const val FADE_MS = 10

fun frame(
    index: Int,
    frameSamples: Int = SpeakerEngine.FRAME_SAMPLES,
    sampleRate: Int = SpeakerEngine.SAMPLE_RATE,
): ShortArray {
    val perWindow = frameSamples / 2
    val perNote = perWindow * FRAMES / 2
    val fadeSamples = sampleRate * FADE_MS / MS_PER_SECOND
    val out = ShortArray(frameSamples)
    for (i in 0 until perWindow) {
        val n = index * perWindow + i
        val inNote = n % perNote
        val hz = if (n < perNote) FIRST_HZ else SECOND_HZ
        val envelope = min(1.0, min(inNote, perNote - 1 - inNote).toDouble() / fadeSamples)
        val sample = (sin(2.0 * PI * hz * inNote / sampleRate) * AMPLITUDE * envelope * Short.MAX_VALUE).toInt()
        out[2 * i] = sample.toShort()
        out[2 * i + 1] = sample.toShort()
    }
    return out
}

class SpeakerTestTone internal constructor(
    private val sink: SpeakerPlayoutSink,
    private val routing: SlotAudioRoutes,
    private val frames: Int,
) {
    @Inject
    constructor(sink: AudioTrackSpeakerSink, routing: PadAudioRouting) : this(sink, routing, FRAMES)

    /**
     * The tone on one of the pad's lanes: the speaker test on [PlayoutLane.SPEAKER], the haptics test
     * on [PlayoutLane.HAPTICS]. It opens the endpoint at its own width and writes the lane's pair, as
     * the engine's voices do, so a DualSense hears the speaker test on its speaker and feels the
     * haptics test in its actuators; false when the device would not open that lane.
     */
    suspend fun play(
        slotId: String,
        lane: PlayoutLane,
    ): Boolean {
        val route = routing.forSlot(slotId)
        val channels = endpointChannelsOf(route.playbackChannels)
        val session = sink.open(SpeakerEngine.FRAME_SAMPLES, route.playbackDeviceId, channels, lane) ?: return false
        try {
            for (index in 0 until frames) {
                session.write(frame(index))
                if (index >= PRIMED_FRAMES) delay(FRAME_MS)
            }
            delay(DRAIN_MS)
        } finally {
            session.close()
        }
        return true
    }

    private companion object {
        const val PRIMED_FRAMES = 3
        const val DRAIN_MS = 120L
    }
}

private const val MS_PER_SECOND = 1000
