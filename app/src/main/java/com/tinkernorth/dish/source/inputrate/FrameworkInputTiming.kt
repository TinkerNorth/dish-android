// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class FrameworkTimingSummary(
    val samples: Int,
    val gapP50Ms: Float,
    val gapP99Ms: Float,
    val delayP50Ms: Float,
    val delayP99Ms: Float,
)

object Percentiles {
    // Nearest-rank on a sorted copy, the same rule the native bench uses.
    fun of(
        values: FloatArray,
        p: Double,
    ): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        val index = (p * (sorted.size - 1) + 0.5).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

class FrameworkTimingWindow(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val gaps = FloatArray(capacity)
    private val delays = FloatArray(capacity)
    private var next = 0
    private var count = 0
    private var seeded = false
    private var lastEventTimeMs = 0L

    @Synchronized
    fun record(
        eventTimeMs: Long,
        nowMs: Long,
    ) {
        val gap = if (seeded) eventTimeMs - lastEventTimeMs else -1L
        seeded = true
        lastEventTimeMs = eventTimeMs
        if (gap < 0 || gap > MAX_GAP_MS) return
        gaps[next] = gap.toFloat()
        delays[next] = (nowMs - eventTimeMs).coerceAtLeast(0L).toFloat()
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun summary(): FrameworkTimingSummary? {
        if (count == 0) return null
        val g = gaps.copyOf(count)
        val d = delays.copyOf(count)
        return FrameworkTimingSummary(
            samples = count,
            gapP50Ms = Percentiles.of(g, P50),
            gapP99Ms = Percentiles.of(g, P99),
            delayP50Ms = Percentiles.of(d, P50),
            delayP99Ms = Percentiles.of(d, P99),
        )
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAX_GAP_MS = 100L
        const val P50 = 0.5
        const val P99 = 0.99
    }
}

// Framework (USB Standard / Bluetooth) pads deliver MotionEvents stamped on the uptime clock;
// the gap between stamps is the pad's own cadence and stamp-to-dispatch is what Android added.
@Singleton
class FrameworkInputTimingStore
    @Inject
    constructor() {
        private val armed = AtomicInteger(0)
        private val windows = ConcurrentHashMap<Int, FrameworkTimingWindow>()

        val enabled: Boolean get() = armed.get() > 0

        fun arm() {
            armed.incrementAndGet()
        }

        fun disarm() {
            armed.updateAndGet { (it - 1).coerceAtLeast(0) }
        }

        fun record(
            deviceId: Int,
            eventTimeMs: Long,
            nowMs: Long,
        ) {
            if (!enabled) return
            windows.computeIfAbsent(deviceId) { FrameworkTimingWindow() }.record(eventTimeMs, nowMs)
        }

        fun summary(deviceId: Int): FrameworkTimingSummary? = windows[deviceId]?.summary()

        fun forget(deviceId: Int) {
            windows.remove(deviceId)
        }
    }
