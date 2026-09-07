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

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): SpeakerPlayoutSession? {
            if (refuse) return null
            openedEndpoint = preferredDeviceId
            return object : SpeakerPlayoutSession {
                override fun write(pcmStereo: ShortArray): Int {
                    written += pcmStereo
                    return pcmStereo.size
                }

                override fun close() {
                    closed = true
                }
            }
        }
    }

    private val routing =
        object : SlotAudioRoutes {
            override val changes = MutableStateFlow(emptyMap<Int, PadAudioRoute>())

            override fun forSlot(slotId: String): PadAudioRoute =
                if (slotId == "-5") PadAudioRoute(microphone = false, speaker = true, playbackDeviceId = 42) else PadAudioRoute.NONE
        }

    @Test
    fun `plays every frame on the pad's own endpoint and closes the track`() =
        runTest {
            val sink = FakeSink()
            assertTrue(SpeakerTestTone(sink, routing, TestTonePolicy.FRAMES).play("-5"))
            assertEquals(42, sink.openedEndpoint)
            assertEquals(TestTonePolicy.FRAMES, sink.written.size)
            assertTrue(sink.written.all { it.size == SpeakerEngine.FRAME_SAMPLES })
            assertTrue(sink.closed)
        }

    @Test
    fun `the phone route plays out the default output`() =
        runTest {
            val sink = FakeSink()
            assertTrue(SpeakerTestTone(sink, routing, TestTonePolicy.FRAMES).play("virtual"))
            assertEquals(NO_AUDIO_DEVICE, sink.openedEndpoint)
        }

    @Test
    fun `a refused output reports false and writes nothing`() =
        runTest {
            val sink = FakeSink(refuse = true)
            assertFalse(SpeakerTestTone(sink, routing, TestTonePolicy.FRAMES).play("-5"))
            assertTrue(sink.written.isEmpty())
        }

    @Test
    fun `the tone fades in from silence, stays under full scale and keeps both channels equal`() {
        val first = TestTonePolicy.frame(0)
        assertEquals(0, first[0].toInt())
        assertEquals(0, first[1].toInt())
        val last = TestTonePolicy.frame(TestTonePolicy.FRAMES - 1)
        assertEquals(0, last[last.size - 2].toInt())
        var peak = 0
        for (index in 0 until TestTonePolicy.FRAMES) {
            val frame = TestTonePolicy.frame(index)
            for (i in 0 until frame.size step 2) {
                assertEquals(frame[i], frame[i + 1])
                peak = maxOf(peak, abs(frame[i].toInt()))
            }
        }
        assertTrue(peak > Short.MAX_VALUE / 4)
        assertTrue(peak < Short.MAX_VALUE / 2)
    }
}
