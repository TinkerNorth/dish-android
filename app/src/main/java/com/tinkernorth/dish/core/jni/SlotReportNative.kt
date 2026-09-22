// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * What a bound slot streams over a live session: pad reports, motion, battery, touchpad
 * frames and microphone audio, with the per-slot send tallies the diagnostics read.
 * Every send is a hot path, so the signatures stay flat primitives: no per-event allocation.
 */
object SlotReportNative {
    init {
        System.loadLibrary("satellite")
    }

    external fun sendReport(
        handle: Int,
        controllerIndex: Int,
        wButtons: Int,
        bLeftTrigger: Int,
        bRightTrigger: Int,
        sThumbLX: Int,
        sThumbLY: Int,
        sThumbRX: Int,
        sThumbRY: Int,
    )

    // Cemuhook DSU axes; gyro LSB = 2000/32767 deg/s, accel LSB = 4/32767 g.
    external fun sendMotion(
        handle: Int,
        controllerIndex: Int,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    )

    // level: 0..100 or 0xFF (unknown); status: BatteryValidator.BATTERY_STATUS_*.
    external fun sendBattery(
        handle: Int,
        controllerIndex: Int,
        level: Int,
        status: Int,
    )

    // Coords are normalized int16 (-32768..32767); receiver maps to active touchpad mode space.
    // rightPressed/middlePressed/scrollDelta only mean anything in mouse mode; scrollDelta is
    // signed with 120 per wheel notch and older satellites ignore all three.
    // Marker: this is the JNI edge of the touch hot path (every finger sample, up to the screen's
    // sampling rate). The Kotlin side carries the frame as a TouchpadReport and unpacks it here
    // (ControllerRepository) so that native reads primitives straight off the stack: a parameter
    // object would cost a GetFieldID plus a Get*Field call per field, per sample.
    @Suppress("LongParameterList")
    external fun sendTouchpad(
        handle: Int,
        controllerIndex: Int,
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

    // One 20 ms mono window (exactly 960 samples at 48 kHz, signed 16-bit) from the
    // capture thread: native encodes it to Opus and sends MSG_MIC_AUDIO. False means
    // nothing left the device (no session, wrong window size, or no encoder), which
    // is the only answer the caller can act on: the stream itself is lossy by
    // contract, so a sent frame carries no delivery promise either.
    external fun sendMicFrame(
        handle: Int,
        controllerIndex: Int,
        pcmMono: ShortArray,
    ): Boolean

    external fun getSlotSendCount(
        handle: Int,
        controllerIndex: Int,
    ): Long

    external fun getSlotMotionCount(
        handle: Int,
        controllerIndex: Int,
    ): Long
}
