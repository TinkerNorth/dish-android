// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlin.math.abs
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
// Angle buckets for the circularity sweep. 16 is coarse enough that a normal-speed
// hand sweep fills every bucket, fine enough to catch a flat spot on one side.
private const val BUCKETS = 16

// A sweep sample counts toward circularity only past this magnitude: inner travel is
// the user moving, not the stick's rim.
private const val RIM_THRESHOLD = 0.5f

// Buckets that must be visited before a circularity verdict; fewer means the user
// never completed the circle and a number would be noise.
private const val MIN_COVERED_BUCKETS = 12

private const val DEADZONE_HEADROOM = 1.5f
private const val DEADZONE_MIN = 0.04f
private const val DEADZONE_MAX = 0.30f
private const val PERCENT = 100
private const val MS_PER_SECOND = 1000L

/** Mean resting offset magnitude; the stick was supposed to be untouched. */
internal fun drift(samples: List<StickSample>): Float {
    if (samples.isEmpty()) return 0f
    val sum = samples.fold(0f) { acc, s -> acc + s.magnitude }
    return sum / samples.size
}

internal fun suggestedDeadzone(drift: Float): Float {
    val suggested = drift * DEADZONE_HEADROOM
    val clamped = suggested.coerceIn(DEADZONE_MIN, DEADZONE_MAX)
    return (clamped * PERCENT).roundToInt() / PERCENT.toFloat()
}

internal data class Envelope(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    // Max deviation from the mean rim radius as a fraction of it; null until the
    // sweep covered enough of the circle to judge.
    val circularityError: Float?,
)

// The furthest the stick reached in each direction around the circle. Only samples out at
// the rim describe the gate's shape; anything closer in is where the stick happened to be,
// not the limit of where it can go.
private class RimBuckets {
    val max = FloatArray(BUCKETS)
    val seen = BooleanArray(BUCKETS)

    fun add(sample: StickSample) {
        val mag = sample.magnitude
        if (mag < RIM_THRESHOLD) return
        val angle = atan2(sample.y, sample.x)
        val bucket = (((angle + Math.PI) / (2 * Math.PI)) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
        seen[bucket] = true
        if (mag > max[bucket]) max[bucket] = mag
    }
}

internal fun envelope(samples: List<StickSample>): Envelope {
    val rim = RimBuckets()
    samples.forEach(rim::add)
    val minX = samples.fold(0f) { acc, s -> minOf(acc, s.x) }
    val maxX = samples.fold(0f) { acc, s -> maxOf(acc, s.x) }
    val minY = samples.fold(0f) { acc, s -> minOf(acc, s.y) }
    val maxY = samples.fold(0f) { acc, s -> maxOf(acc, s.y) }
    return Envelope(minX, maxX, minY, maxY, circularityError(rim.max, rim.seen))
}

internal fun circularityError(
    bucketMax: FloatArray,
    bucketSeen: BooleanArray,
): Float? {
    val covered = bucketSeen.count { it }
    if (covered < MIN_COVERED_BUCKETS) return null
    val seenMaxima = bucketMax.filterIndexed { i, _ -> bucketSeen[i] }
    val mean = seenMaxima.sum() / covered
    if (mean <= 0f) return null
    val worst = seenMaxima.maxOf { abs(it - mean) }
    return worst / mean
}

// The rail the stick struggles to reach is the one that matters in game.
internal fun worstReach(e: Envelope): Float = minOf(-e.minX, e.maxX, -e.minY, e.maxY).coerceAtLeast(0f)

internal enum class CaptureKind { DRIFT, RANGE }

internal sealed interface CaptureTick {
    data class Counting(
        val secondsLeft: Int,
    ) : CaptureTick

    data class Finished(
        val kind: CaptureKind,
    ) : CaptureTick
}

// A capture still running shows the whole seconds left, rounded up so it never reads zero
// while samples are still being taken; one that ran out finishes as the kind it was.
internal fun captureTick(
    kind: CaptureKind,
    leftMs: Long,
): CaptureTick {
    if (leftMs > 0) return CaptureTick.Counting((leftMs / MS_PER_SECOND + 1).toInt())
    return CaptureTick.Finished(kind)
}
