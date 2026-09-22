// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

/**
 * One full-state touchpad frame as the wire carries it (MSG_TOUCHPAD, satellite
 * docs/contract.md): two tracked fingers in normalized int16 space, the pad click
 * and the mouse-mode buttons, the sample time and the wheel delta.
 *
 * Coords are -32768..32767; the receiver maps them into its active touchpad mode.
 * [rightPressed], [middlePressed] and [scrollDelta] only mean anything in mouse
 * mode; scrollDelta is signed with 120 per wheel notch and older satellites ignore
 * all three. [eventTimeMs] is the sensor sample time: resends carry the same
 * value so the receiver can drop duplicates by equality.
 */
data class TouchpadReport(
    val finger0Active: Boolean,
    val finger1Active: Boolean,
    val buttonPressed: Boolean,
    val rightPressed: Boolean,
    val middlePressed: Boolean,
    val finger0TrackingId: Int,
    val finger0X: Short,
    val finger0Y: Short,
    val finger1TrackingId: Int,
    val finger1X: Short,
    val finger1Y: Short,
    val eventTimeMs: Long,
    val scrollDelta: Short,
)
