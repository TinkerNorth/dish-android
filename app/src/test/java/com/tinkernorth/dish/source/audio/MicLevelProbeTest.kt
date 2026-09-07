// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicLevelProbeTest {
    private class FakeMic(
        private val windows: Int,
        private val refuse: Boolean = false,
        private val amplitude: Int = 1000,
    ) : MicCaptureSource {
        var openedEndpoint: Int? = null
        var closed = false

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession? {
            if (refuse) return null
            openedEndpoint = preferredDeviceId
            return object : MicCaptureSession {
                private var served = 0
                override val voiceProcessed = true

                override fun read(out: ShortArray): Int {
                    if (served >= windows) return 0
                    served++
                    out.fill(amplitude.toShort())
                    return out.size
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
                if (slotId == "-5") PadAudioRoute(microphone = true, speaker = false, captureDeviceId = 7) else PadAudioRoute.NONE
        }

    private fun kotlinx.coroutines.test.TestScope.probe(mic: FakeMic) =
        MicLevelProbe(mic, routing, StandardTestDispatcher(testScheduler), MicEngine.FRAME_SAMPLES)

    @Test
    fun `levels each window from the pad's own endpoint and reports a dead recorder as unavailable`() =
        runTest {
            val mic = FakeMic(windows = 2)
            val readings = probe(mic).readings("-5").toList()
            assertEquals(7, mic.openedEndpoint)
            assertEquals(3, readings.size)
            assertTrue(readings[0] is MicProbeReading.Level)
            assertTrue(readings[1] is MicProbeReading.Level)
            assertEquals(MicProbeReading.Unavailable, readings[2])
            assertTrue(mic.closed)
        }

    @Test
    fun `a refused microphone is one unavailable reading`() =
        runTest {
            val mic = FakeMic(windows = 5, refuse = true)
            val readings = probe(mic).readings("virtual").toList()
            assertEquals(listOf(MicProbeReading.Unavailable), readings)
        }

    @Test
    fun `stopping the collector closes the microphone`() =
        runTest {
            val mic = FakeMic(windows = Int.MAX_VALUE)
            val readings = probe(mic).readings("virtual").take(2).toList()
            assertEquals(2, readings.size)
            assertEquals(NO_AUDIO_DEVICE, mic.openedEndpoint)
            assertTrue(mic.closed)
        }

    @Test
    fun `the meter maps silence to zero and full scale to one`() {
        val silence = MicLevelMeter.level(ShortArray(960))
        assertEquals(0f, silence.rms, 0f)
        assertEquals(0f, silence.peak, 0f)
        assertEquals(0f, silence.meter, 0f)

        val loud = MicLevelMeter.level(ShortArray(960) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE })
        assertEquals(1f, loud.peak, 0.001f)
        assertEquals(1f, loud.rms, 0.001f)
        assertEquals(1f, loud.meter, 0.001f)
    }

    @Test
    fun `the meter is a clamped decibel scale over a 60 dB floor`() {
        assertEquals(0f, MicLevelMeter.meter(0.0005f), 0f)
        assertEquals(0.5f, MicLevelMeter.meter(0.0316f), 0.01f)
        assertTrue(MicLevelMeter.meter(0.1f) > MicLevelMeter.meter(0.01f))
    }
}
