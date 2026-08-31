// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

/**
 * Where a slot's outbound telemetry (motion / battery / pad-touch) lands.
 * [SatelliteConnection] streams it over the encrypted UDP session;
 * [com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection]
 * translates it onto the control stream (CONTROLLER_MOTION / _BATTERY /
 * _TOUCH). Sources resolve a sink per slot through the reachability composer
 * and stay ignorant of the transport.
 */
interface TelemetrySink {
    /**
     * Whether the destination currently consumes motion for this slot. A
     * satellite always does (the descriptor advertised CAP_MOTION); a Moonlight
     * host must have asked via MOTION_EVENT first, so a source can keep the
     * phone/pad IMU asleep until then.
     */
    fun motionWanted(slotId: String): Boolean = true

    @Suppress("LongParameterList")
    fun sendMotion(
        slotId: String,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    )

    fun sendBattery(
        slotId: String,
        level: Int,
        status: Int,
    )

    @Suppress("LongParameterList")
    fun sendTouchpad(
        slotId: String,
        finger0Active: Boolean,
        finger1Active: Boolean,
        buttonPressed: Boolean,
        rightPressed: Boolean,
        middlePressed: Boolean,
        finger0TrackingId: Int,
        finger0X: Short,
        finger0Y: Short,
        finger1TrackingId: Int,
        finger1X: Short,
        finger1Y: Short,
        eventTimeMs: Long,
        scrollDelta: Short,
    )
}
