// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerTestToneTest {
    private class FakeSink(
        private val refuse: Boolean = false,
    ) : SpeakerPlayoutSink {
        var openedEndpoint: Int? = null
        val written = mutableListOf<ShortArray>()
        var closed = false

        var openedChannels: Int? = null
        var openedLane: PlayoutLane? = null

        // Accepts every frame whole, so a short write never stands in for the assertion that the
        // tone reached the track.
        private inner class WritingSession : SpeakerPlayoutSession {
            override fun write(pcmStereo: ShortArray): Int {
                written += pcmStereo
                return pcmStereo.size
            }

            override fun close() {
                closed = true
            }
        }

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
            channels: Int,
            lane: PlayoutLane,
        ): SpeakerPlayoutSession? {
            if (refuse) return null
            openedEndpoint = preferredDeviceId
            openedChannels = channels
            openedLane = lane
            return WritingSession()
        }
    }

    private val routing =
        MapSlotAudioRoutes(
            MutableStateFlow(emptyMap()),
            mapOf("-5" to PadAudioRoute(microphone = false, speaker = true, playbackDeviceId = 42)),
        )

    // A DualSense's own endpoint: four channels, the speaker pair then the actuator pair.
    private val dualSenseRouting =
        MapSlotAudioRoutes(
            MutableStateFlow(emptyMap()),
            mapOf(
                "-5" to
                    PadAudioRoute(
                        microphone = false,
                        speaker = true,
                        playbackDeviceId = 42,
                        haptics = true,
                        playbackChannels = PlayoutLane.QUAD_CHANNELS,
                    ),
            ),
        )

    @Test
    fun `the haptics test plays every frame on a DualSense's actuator pair at its endpoint's width`() =
        runTest {
            val sink = FakeSink()
            assertTrue(SpeakerTestTone(sink, dualSenseRouting, FRAMES).play("-5", PlayoutLane.HAPTICS))
            assertEquals(PlayoutLane.HAPTICS, sink.openedLane)
            assertEquals(PlayoutLane.QUAD_CHANNELS, sink.openedChannels)
            assertEquals(42, sink.openedEndpoint)
            assertEquals(FRAMES, sink.written.size)
            assertTrue(sink.closed)
        }

    @Test
    fun `the speaker test on a DualSense keeps the tone on its speaker pair`() =
        runTest {
            val sink = FakeSink()
            assertTrue(SpeakerTestTone(sink, dualSenseRouting, FRAMES).play("-5", PlayoutLane.SPEAKER))
            assertEquals(PlayoutLane.SPEAKER, sink.openedLane)
            assertEquals(PlayoutLane.QUAD_CHANNELS, sink.openedChannels)
        }

    @Test
    fun `plays every frame on the pad's own endpoint and closes the track`() =
        runTest {
            val sink = FakeSink()
            assertTrue(SpeakerTestTone(sink, routing, FRAMES).play("-5", PlayoutLane.SPEAKER))
            assertEquals(42, sink.openedEndpoint)
            assertEquals(FRAMES, sink.written.size)
            assertTrue(sink.written.all { it.size == SpeakerEngine.FRAME_SAMPLES })
            assertTrue(sink.closed)
        }

    @Test
    fun `the phone route plays out the default output`() =
        runTest {
            val sink = FakeSink()
            assertTrue(SpeakerTestTone(sink, routing, FRAMES).play("virtual", PlayoutLane.SPEAKER))
            assertEquals(NO_AUDIO_DEVICE, sink.openedEndpoint)
        }

    @Test
    fun `a refused output reports false and writes nothing`() =
        runTest {
            val sink = FakeSink(refuse = true)
            assertFalse(SpeakerTestTone(sink, routing, FRAMES).play("-5", PlayoutLane.SPEAKER))
            assertTrue(sink.written.isEmpty())
        }

    @Test
    fun `the tone fades in from silence, stays under full scale and keeps both channels equal`() {
        val first = frame(0)
        assertEquals(0, first[0].toInt())
        assertEquals(0, first[1].toInt())
        val last = frame(FRAMES - 1)
        assertEquals(0, last[last.size - 2].toInt())
        var peak = 0
        for (index in 0 until FRAMES) {
            val frame = frame(index)
            for (i in 0 until frame.size step 2) {
                assertEquals(frame[i], frame[i + 1])
                peak = maxOf(peak, abs(frame[i].toInt()))
            }
        }
        assertTrue(peak > Short.MAX_VALUE / 4)
        assertTrue(peak < Short.MAX_VALUE / 2)
    }
}
