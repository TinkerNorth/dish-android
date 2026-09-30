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
 *
 * Mutable because a per-frame sender refills one it owns rather than building one a frame
 * ([com.tinkernorth.dish.ui.common.TouchpadReportBuffer]); a sink reads it during the call and
 * keeps nothing of it ([TelemetrySink.sendTouchpad]).
 */
data class TouchpadReport(
    var finger0Active: Boolean,
    var finger1Active: Boolean,
    var buttonPressed: Boolean,
    var rightPressed: Boolean,
    var middlePressed: Boolean,
    var finger0TrackingId: Int,
    var finger0X: Short,
    var finger0Y: Short,
    var finger1TrackingId: Int,
    var finger1X: Short,
    var finger1Y: Short,
    var eventTimeMs: Long,
    var scrollDelta: Short,
)
