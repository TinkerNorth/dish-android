// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import kotlin.math.roundToInt

/**
 * Pure unit-conversion helpers for the phone IMU → wire path.
 *
 * Android's `SensorManager` reports the gyroscope in rad/s and the
 * accelerometer in m/s² (gravity included), both in the *device* coordinate
 * frame. The satellite wire format ([SatelliteNative.sendMotion]) wants
 * signed int16 in the Cemuhook DSU frame: gyro full-scale ±2000 deg/s,
 * accel full-scale ±4 g, right-handed (+X right, +Y up, +Z toward player).
 *
 * Kept framework-free + pure so the scaling + axis remap can be pinned by
 * unit tests without an Android device — same pattern as the desktop
 * senders' `scaleGyro` / `scaleAccel`.
 */
object MotionScaling {
    /** Standard gravity, m/s² — divisor to convert accelerometer to g. */
    const val GRAVITY_MSS = 9.80665

    /** Gyro full-scale, deg/s — maps to int16 ±32767. */
    const val GYRO_FULL_SCALE_DEG = 2000.0

    /** Accelerometer full-scale, g — maps to int16 ±32767. */
    const val ACCEL_FULL_SCALE_G = 4.0

    private const val RAD_TO_DEG = 180.0 / Math.PI
    private const val INT16_MAX = 32767
    private const val INT16_MIN = -32768

    /** Convert a rad/s gyro reading to the wire int16 (±2000 deg/s full scale). */
    fun gyroRadToWire(radPerSec: Float): Short {
        val degPerSec = radPerSec * RAD_TO_DEG
        val scaled = (degPerSec / GYRO_FULL_SCALE_DEG * INT16_MAX).roundToInt()
        return scaled.coerceIn(INT16_MIN, INT16_MAX).toShort()
    }

    /** Convert an m/s² accelerometer reading to the wire int16 (±4 g full scale). */
    fun accelMssToWire(mss: Float): Short {
        val g = mss / GRAVITY_MSS
        val scaled = (g / ACCEL_FULL_SCALE_G * INT16_MAX).roundToInt()
        return scaled.coerceIn(INT16_MIN, INT16_MAX).toShort()
    }

    /**
     * Remap a device-frame sensor vector into the screen frame for a phone
     * held in landscape (the `GamepadOverlayActivity` is locked landscape,
     * `ROTATION_90` — rotated 90° counter-clockwise from portrait).
     *
     * In that hold the device's +Y points to screen-right and the device's
     * +X points to screen-down, so: screenX = deviceY, screenY = -deviceX,
     * screenZ = deviceZ. The result is already in the DSU frame (+X right,
     * +Y up, +Z toward player), so the receiver does no further rotation.
     */
    fun remapLandscape(
        deviceX: Float,
        deviceY: Float,
        deviceZ: Float,
    ): FloatArray = floatArrayOf(deviceY, -deviceX, deviceZ)
}
