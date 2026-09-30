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
 *
 * Mutable because each thread that handles frames refills one it owns rather than building one
 * an event: the main thread maps every captured event into its own, and the resend thread copies
 * a slot's latest into its own (com.tinkernorth.dish.hotpath.overlay.CapturedTouchFrames).
 */
data class PadTouchFrame(
    var finger0Active: Boolean = false,
    var finger1Active: Boolean = false,
    var buttonPressed: Boolean = false,
    var finger0Id: Int = 0,
    var finger0X: Short = 0,
    var finger0Y: Short = 0,
    var finger1Id: Int = 0,
    var finger1X: Short = 0,
    var finger1Y: Short = 0,
    var eventTimeMs: Long = 0L,
) {
    fun anyFingerDown(): Boolean = finger0Active || finger1Active

    /** Whether the frame still holds a finger or the click, which a capture ending now must lift. */
    fun holdsATouch(): Boolean = anyFingerDown() || buttonPressed

    /** Lifts every finger and the click at [eventTimeMs], keeping the positions. */
    fun lift(eventTimeMs: Long) {
        finger0Active = false
        finger1Active = false
        buttonPressed = false
        this.eventTimeMs = eventTimeMs
    }

    /** Takes every field of [other]. */
    fun copyFrom(other: PadTouchFrame) {
        finger0Active = other.finger0Active
        finger1Active = other.finger1Active
        buttonPressed = other.buttonPressed
        finger0Id = other.finger0Id
        finger0X = other.finger0X
        finger0Y = other.finger0Y
        finger1Id = other.finger1Id
        finger1X = other.finger1X
        finger1Y = other.finger1Y
        eventTimeMs = other.eventTimeMs
    }
}

/**
 * One captured touchpad event as the mapper reads it: its pointers by index, the way a MotionEvent
 * hands them out, and the surface's raw range on each axis. The overlay reads the MotionEvent
 * through one of these in place, so no pointer is copied out of the event.
 *
 * In captured mode Android reports a touchpad "unscaled": each pointer's X/Y is the surface's own
 * raw coordinate, and the device's motion range for that axis (queried while captured) is the
 * raw range. A DualShock 4 reports 0..1919 by 0..941, a DualSense 0..1919 by 0..1079; the range
 * is what the normalisation trusts, never a per-model table.
 */
interface CapturedTouchpadEvent {
    val pointerCount: Int

    fun pointerId(index: Int): Int

    fun x(index: Int): Float

    fun y(index: Int): Float

    val xMin: Float
    val xMax: Float
    val yMin: Float
    val yMax: Float
}

// Which pointer an event takes off the surface: none (a move, a down), the one at the event's
// action index (an up), or all of them (a cancel, a hover).
const val NO_POINTER_LIFTING = -1
const val EVERY_POINTER_LIFTING = -2

// The wire's span, the raw decoder's touchAbsToInt16 in float: 0 at the near edge, 32767 at
// the far one, clamped so a jittery reading one past the range cannot wrap.
fun normalize(
    value: Float,
    min: Float,
    max: Float,
): Short {
    val span = max - min
    if (span <= 0f) return 0
    val unit = ((value - min) / span).coerceIn(0f, 1f)
    return (unit * WIRE_SPAN - WIRE_HALF).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/**
 * Fills [into] with the frame for [event], less the pointer [liftingIndex] takes off. The two
 * finger slots take the two lowest pointer ids still down, lowest first, so a finger keeps its
 * slot while another comes and goes, the way the pad's own report keeps its two touch slots; a
 * third finger is ignored. Every field is written, so nothing of the frame [into] held before
 * survives, and both fingers are read straight into it: nothing is built.
 */
fun frame(
    event: CapturedTouchpadEvent,
    liftingIndex: Int,
    buttonPressed: Boolean,
    eventTimeMs: Long,
    into: PadTouchFrame,
) {
    val first = lowestPointerIndex(event, liftingIndex, passedOver = NO_POINTER)
    val second = lowestPointerIndex(event, liftingIndex, passedOver = first)
    into.finger0Active = first != NO_POINTER
    into.finger1Active = second != NO_POINTER
    into.buttonPressed = buttonPressed
    into.finger0Id = trackingIdAt(event, first)
    into.finger0X = xAt(event, first)
    into.finger0Y = yAt(event, first)
    into.finger1Id = trackingIdAt(event, second)
    into.finger1X = xAt(event, second)
    into.finger1Y = yAt(event, second)
    into.eventTimeMs = eventTimeMs
}

// The index of the lowest pointer id still down, other than [passedOver]; NO_POINTER when none is.
// A cancel or a hover has none, and its pointers are never read.
private fun lowestPointerIndex(
    event: CapturedTouchpadEvent,
    liftingIndex: Int,
    passedOver: Int,
): Int {
    if (liftingIndex == EVERY_POINTER_LIFTING) return NO_POINTER
    var lowest = NO_POINTER
    for (index in 0 until event.pointerCount) {
        val isSkipped = index == liftingIndex || index == passedOver
        if (isSkipped) continue
        val isLowerId = lowest == NO_POINTER || event.pointerId(index) < event.pointerId(lowest)
        if (isLowerId) lowest = index
    }
    return lowest
}

private fun trackingIdAt(
    event: CapturedTouchpadEvent,
    index: Int,
): Int = if (index == NO_POINTER) 0 else event.pointerId(index) and TRACKING_ID_MASK

private fun xAt(
    event: CapturedTouchpadEvent,
    index: Int,
): Short = if (index == NO_POINTER) 0 else normalize(event.x(index), event.xMin, event.xMax)

private fun yAt(
    event: CapturedTouchpadEvent,
    index: Int,
): Short = if (index == NO_POINTER) 0 else normalize(event.y(index), event.yMin, event.yMax)

const val TRACKING_ID_MASK = 0x7F
private const val NO_POINTER = -1
private const val WIRE_SPAN = 65535f
private const val WIRE_HALF = 32768f
