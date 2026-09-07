// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

sealed interface MicProbeReading {
    data object Unavailable : MicProbeReading

    data class Level(
        val rms: Float,
        val peak: Float,
    ) : MicProbeReading {
        val meter: Float get() = MicLevelMeter.meter(rms)
    }
}

object MicLevelMeter {
    private const val FLOOR_DB = -60f

    fun level(window: ShortArray): MicProbeReading.Level {
        if (window.isEmpty()) return MicProbeReading.Level(rms = 0f, peak = 0f)
        var peak = 0
        var sumSquares = 0.0
        for (s in window) {
            val v = s.toInt()
            if (abs(v) > peak) peak = abs(v)
            sumSquares += v.toDouble() * v
        }
        val scale = -Short.MIN_VALUE.toFloat()
        return MicProbeReading.Level(
            rms = (sqrt(sumSquares / window.size) / scale).toFloat(),
            peak = peak / scale,
        )
    }

    fun meter(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20f * log10(rms)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }
}

class MicLevelProbe internal constructor(
    private val source: MicCaptureSource,
    private val routing: SlotAudioRoutes,
    private val io: CoroutineDispatcher,
    private val frameSamples: Int,
) {
    @Inject
    constructor(
        source: AudioRecordMicSource,
        routing: PadAudioRouting,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(source, routing, io, MicEngine.FRAME_SAMPLES)

    fun readings(slotId: String): Flow<MicProbeReading> =
        flow {
            val session = source.open(frameSamples, routing.forSlot(slotId).captureDeviceId)
            if (session == null) {
                emit(MicProbeReading.Unavailable)
                return@flow
            }
            try {
                val window = ShortArray(frameSamples)
                while (currentCoroutineContext().isActive) {
                    if (session.read(window) != window.size) {
                        emit(MicProbeReading.Unavailable)
                        break
                    }
                    emit(MicLevelMeter.level(window))
                }
            } finally {
                session.close()
            }
        }.flowOn(io)
}
