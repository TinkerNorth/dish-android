// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

// One stick reading normalized to -1..1 per axis (wire int16 / 32768).
data class StickSample(
    val x: Float,
    val y: Float,
) {
    val magnitude: Float get() = hypot(x, y)
}

// Reducer: pure stick-health math for the inspector's capture flows. Kept free of Android
// types so drift, envelope, and circularity are unit-testable against synthetic sweeps.
object StickHealth {
    // Angle buckets for the circularity sweep. 16 is coarse enough that a normal-speed
    // hand sweep fills every bucket, fine enough to catch a flat spot on one side.
    private const val BUCKETS = 16

    // A sweep sample counts toward circularity only past this magnitude: inner travel is
    // the user moving, not the stick's rim.
    private const val RIM_THRESHOLD = 0.5f

    // Buckets that must be visited before a circularity verdict; fewer means the user
    // never completed the circle and a number would be noise.
    private const val MIN_COVERED_BUCKETS = 12

    /** Mean resting offset magnitude; the stick was supposed to be untouched. */
    fun drift(samples: List<StickSample>): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0f
        for (s in samples) sum += s.magnitude
        return sum / samples.size
    }

    // 1.5x headroom over the observed drift so noise peaks above the mean stay inside;
    // floored at 4% (sensor noise on a healthy stick) and capped at 30% (beyond that the
    // stick is faulty and hiding it would eat real input).
    fun suggestedDeadzone(drift: Float): Float {
        val suggested = drift * 1.5f
        val clamped = suggested.coerceIn(0.04f, 0.30f)
        return (clamped * 100).roundToInt() / 100f
    }

    data class Envelope(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        // Max deviation from the mean rim radius as a fraction of it; null until the
        // sweep covered enough of the circle to judge.
        val circularityError: Float?,
    )

    fun envelope(samples: List<StickSample>): Envelope {
        var minX = 0f
        var maxX = 0f
        var minY = 0f
        var maxY = 0f
        val bucketMax = FloatArray(BUCKETS)
        val bucketSeen = BooleanArray(BUCKETS)
        for (s in samples) {
            if (s.x < minX) minX = s.x
            if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y
            if (s.y > maxY) maxY = s.y
            val mag = s.magnitude
            if (mag >= RIM_THRESHOLD) {
                val angle = atan2(s.y, s.x)
                val bucket = (((angle + Math.PI) / (2 * Math.PI)) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                bucketSeen[bucket] = true
                if (mag > bucketMax[bucket]) bucketMax[bucket] = mag
            }
        }
        return Envelope(minX, maxX, minY, maxY, circularityError(bucketMax, bucketSeen))
    }

    private fun circularityError(
        bucketMax: FloatArray,
        bucketSeen: BooleanArray,
    ): Float? {
        val covered = bucketSeen.count { it }
        if (covered < MIN_COVERED_BUCKETS) return null
        var sum = 0f
        for (i in bucketMax.indices) if (bucketSeen[i]) sum += bucketMax[i]
        val mean = sum / covered
        if (mean <= 0f) return null
        var worst = 0f
        for (i in bucketMax.indices) {
            if (!bucketSeen[i]) continue
            val deviation = kotlin.math.abs(bucketMax[i] - mean)
            if (deviation > worst) worst = deviation
        }
        return worst / mean
    }
}
