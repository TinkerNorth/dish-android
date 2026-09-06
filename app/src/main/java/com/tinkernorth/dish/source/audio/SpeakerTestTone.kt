// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

object TestTonePolicy {
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
        val fadeSamples = sampleRate * FADE_MS / 1000
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
}

class SpeakerTestTone internal constructor(
    private val sink: SpeakerPlayoutSink,
    private val routing: SlotAudioRoutes,
    private val frames: Int,
) {
    @Inject
    constructor(sink: AudioTrackSpeakerSink, routing: PadAudioRouting) : this(sink, routing, TestTonePolicy.FRAMES)

    suspend fun play(slotId: String): Boolean {
        val session = sink.open(SpeakerEngine.FRAME_SAMPLES, routing.forSlot(slotId).playbackDeviceId) ?: return false
        try {
            for (index in 0 until frames) {
                session.write(TestTonePolicy.frame(index))
                if (index >= PRIMED_FRAMES) delay(TestTonePolicy.FRAME_MS)
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
