// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.source.sensor.MotionRateLimiter.MotionSample
import java.util.concurrent.ConcurrentHashMap

class MotionRateLimiter(
    private val nowUs: () -> Long = { System.nanoTime() / 1_000L },
) {
    data class MotionSample(
        val gyroX: Short,
        val gyroY: Short,
        val gyroZ: Short,
        val accelX: Short,
        val accelY: Short,
        val accelZ: Short,
    )

    fun interface Emit {
        fun emit(
            sample: MotionSample,
            timestampDeltaUs: Int,
        )
    }

    // `hasEmitted` distinguishes first sample from a legitimate `lastEmitUs == 0` clock reading.
    private class State(
        var lastEmitUs: Long = 0L,
        var hasEmitted: Boolean = false,
    )

    private val states = ConcurrentHashMap<Int, State>()

    fun publish(
        controllerIndex: Int,
        sample: MotionSample,
        emit: Emit,
    ): Boolean {
        val state = states.computeIfAbsent(controllerIndex) { State() }
        synchronized(state) {
            val now = nowUs()
            if (state.hasEmitted && now - state.lastEmitUs < MIN_INTERVAL_US) {
                return false
            }
            val deltaUs =
                if (!state.hasEmitted) {
                    0
                } else {
                    val d = now - state.lastEmitUs
                    if (d > UINT32_MAX) UINT32_MAX.toInt() else d.toInt()
                }
            state.lastEmitUs = now
            state.hasEmitted = true
            emit.emit(sample, deltaUs)
            return true
        }
    }

    fun clear(controllerIndex: Int) {
        states.remove(controllerIndex)
    }

    fun clearAll() {
        states.clear()
    }

    companion object {
        const val RATE_LIMIT_HZ = 250

        const val MIN_INTERVAL_US: Long = 1_000_000L / RATE_LIMIT_HZ

        private const val UINT32_MAX: Long = 0xFFFFFFFFL
    }
}
