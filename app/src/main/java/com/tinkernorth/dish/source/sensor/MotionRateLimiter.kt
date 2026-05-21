// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.source.sensor.MotionRateLimiter.MotionSample
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-controller rate limiter for [SatelliteNative.sendMotion].
 *
 * The competitive roadmap acceptance criterion is "motion packets are
 * rate-limited to ≤ 250 Hz by default" (Task 1.1, Tier 1). We measure the
 * gate from the **last emitted** packet — never from the last attempt —
 * because a hot stream of dropped samples would otherwise push the gate
 * forward and starve the legitimate sender past one period.
 *
 * Thread-safety: callable from any thread; per-controller state lives in a
 * [ConcurrentHashMap] and the per-key update path is locked via
 * [synchronized] on that key's [State] instance so concurrent samples for
 * different controllers don't serialise.
 */
class MotionRateLimiter(
    /** Source of monotonic microseconds. Overridable for tests. */
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

    /** Invoked when a sample passes the gate. `timestampDeltaUs` is 0 on first emit. */
    fun interface Emit {
        fun emit(
            sample: MotionSample,
            timestampDeltaUs: Int,
        )
    }

    // `hasEmitted` is a distinct flag rather than a `lastEmitUs == 0`
    // sentinel: a monotonic clock can legitimately read 0 on the very first
    // sample (and test clocks routinely start there), which would otherwise
    // make the second sample look like another "first sample".
    private class State(
        var lastEmitUs: Long = 0L,
        var hasEmitted: Boolean = false,
    )

    private val states = ConcurrentHashMap<Int, State>()

    /**
     * Attempt to emit [sample] for [controllerIndex]. Returns true if the
     * sample was forwarded to [emit]; false if the rate-limit gate dropped it.
     */
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

    /** Drop all per-controller state (called when a controller unplugs). */
    fun clear(controllerIndex: Int) {
        states.remove(controllerIndex)
    }

    /** Drop all per-controller state (called on shutdown). */
    fun clearAll() {
        states.clear()
    }

    companion object {
        /** Per-controller emit cap. The roadmap-documented default is 250 Hz. */
        const val RATE_LIMIT_HZ = 250

        /** Minimum µs between two emitted samples for the same controller. */
        const val MIN_INTERVAL_US: Long = 1_000_000L / RATE_LIMIT_HZ

        /** uint32 max as a long, used to clamp the delta. */
        private const val UINT32_MAX: Long = 0xFFFFFFFFL
    }
}
