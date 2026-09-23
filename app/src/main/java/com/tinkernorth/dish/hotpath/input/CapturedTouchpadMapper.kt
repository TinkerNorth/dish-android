// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

/**
 * One MSG_TOUCHPAD frame built from a pad's own touch surface as Android hands it to a view
 * holding pointer capture: up to two fingers, absolute, plus the surface's click.
 *
 * Coordinates are the wire's signed int16 span (0 -> -32768, the surface's far edge -> 32767),
 * the same normalisation the USB-direct decoder applies to the raw report, so the satellite
 * sees one shape whichever path read the pad. Tracking ids are the pointer ids Android assigned,
 * masked the way the raw decoder masks the pad's own (7 bits), for the same reason.
 */
data class PadTouchFrame(
    val finger0Active: Boolean = false,
    val finger1Active: Boolean = false,
    val buttonPressed: Boolean = false,
    val finger0Id: Int = 0,
    val finger0X: Short = 0,
    val finger0Y: Short = 0,
    val finger1Id: Int = 0,
    val finger1X: Short = 0,
    val finger1Y: Short = 0,
    val eventTimeMs: Long = 0L,
) {
    fun anyFingerDown(): Boolean = finger0Active || finger1Active

    /** The same frame with every finger lifted, for a capture that ends mid-gesture. */
    fun lifted(eventTimeMs: Long): PadTouchFrame =
        copy(finger0Active = false, finger1Active = false, buttonPressed = false, eventTimeMs = eventTimeMs)
}

/**
 * Pure: turns a captured touchpad event's facts into a [PadTouchFrame]. Framework-free so the
 * arithmetic is JVM-tested; the activity host strips the MotionEvent down to these inputs.
 *
 * In captured mode Android reports a touchpad "unscaled": each pointer's X/Y is the surface's own
 * raw coordinate, and the device's motion range for that axis (queried while captured) is the
 * raw range. A DualShock 4 reports 0..1919 by 0..941, a DualSense 0..1919 by 0..1079; the range
 * is what the normalisation trusts, never a per-model table.
 */
data class Pointer(
    val id: Int,
    val x: Float,
    val y: Float,
)

data class Range(
    val min: Float,
    val max: Float,
)

// The wire's span, the raw decoder's touchAbsToInt16 in float: 0 at the near edge, 32767 at
// the far one, clamped so a jittery reading one past the range cannot wrap.
fun normalize(
    value: Float,
    range: Range,
): Short {
    val span = range.max - range.min
    if (span <= 0f) return 0
    val unit = ((value - range.min) / span).coerceIn(0f, 1f)
    return (unit * WIRE_SPAN - WIRE_HALF).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/**
 * [down] is every pointer still on the surface after this event (the caller has already
 * dropped the one lifting on an UP / POINTER_UP, and all of them on a CANCEL). The two
 * finger slots are filled in pointer-id order so a finger keeps its slot while another comes
 * and goes, the way the pad's own report keeps its two touch slots.
 */
fun frame(
    down: List<Pointer>,
    xRange: Range,
    yRange: Range,
    buttonPressed: Boolean,
    eventTimeMs: Long,
): PadTouchFrame {
    val fingers = down.sortedBy { it.id }.take(MAX_FINGERS)
    val f0 = fingers.getOrNull(0)
    val f1 = fingers.getOrNull(1)
    return PadTouchFrame(
        finger0Active = f0 != null,
        finger1Active = f1 != null,
        buttonPressed = buttonPressed,
        finger0Id = (f0?.id ?: 0) and TRACKING_ID_MASK,
        finger0X = f0?.let { normalize(it.x, xRange) } ?: 0,
        finger0Y = f0?.let { normalize(it.y, yRange) } ?: 0,
        finger1Id = (f1?.id ?: 0) and TRACKING_ID_MASK,
        finger1X = f1?.let { normalize(it.x, xRange) } ?: 0,
        finger1Y = f1?.let { normalize(it.y, yRange) } ?: 0,
        eventTimeMs = eventTimeMs,
    )
}

const val MAX_FINGERS = 2
const val TRACKING_ID_MASK = 0x7F
private const val WIRE_SPAN = 65535f
private const val WIRE_HALF = 32768f
