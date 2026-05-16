// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Captures the phone's gyroscope + accelerometer and forwards IMU samples
 * for the on-screen touch controller (`GamepadOverlayActivity`).
 *
 * This is the marquee Android motion use case from the roadmap (Task 1.1):
 * a phone in touch-overlay mode becomes a motion source — gyro aim for
 * shooters, tilt for emulators. Physical gamepads that expose an IMU through
 * the Android `InputDevice` API are a separate, later path.
 *
 * Pipeline: `SensorManager` (rad/s, m/s², device frame) → [MotionScaling]
 * (DSU int16, screen frame) → [MotionRateLimiter] (≤ 250 Hz gate) → [Emit].
 *
 * The accelerometer and gyroscope arrive on separate callbacks; we cache the
 * latest accel triple and emit a fused sample on each gyro tick (gyro is the
 * higher-rate, latency-critical signal). Both sensor callbacks run on the
 * same `SensorManager` thread, so the accel cache itself needs no lock.
 *
 * Threading: the sensor callbacks run on the `SensorManager` thread while
 * [start]/[stop] run on the caller's (main) thread. Fields touched by both —
 * [emit], [started] and the [accelX]/[accelY]/[accelZ] cache that [stop]
 * clears — are `@Volatile` so that cross-thread hand-off is visible. The
 * accel cache being written *and* read on the sensor thread needs no
 * synchronisation; the volatile is purely for the [stop]-side reset.
 *
 * [rotationSupplier] is read once per [start] (not per sample) so a device
 * rotation between activity resumes is picked up on the next stream start
 * without paying a `Display` query on every gyro tick.
 */
class PhoneMotionSource(
    private val sensorManager: SensorManager,
    private val rotationSupplier: () -> Int = { DEFAULT_ROTATION },
    private val rateLimiter: MotionRateLimiter = MotionRateLimiter(),
) {
    /** Invoked when a fused sample passes the rate-limit gate. */
    fun interface Emit {
        fun emit(
            sample: MotionRateLimiter.MotionSample,
            timestampDeltaUs: Int,
        )
    }

    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * True when the phone has a gyroscope. The overlay reads this to show a
     * "Motion: detected / not available" indicator — the "gyro detected"
     * feedback every comparable tool surfaces. Without a gyro there's nothing
     * meaningful to forward, so [start] is a no-op.
     */
    val isAvailable: Boolean get() = gyro != null

    /**
     * True between [start] and [stop] — i.e. the sensor listeners are
     * registered and gyro samples are being forwarded. The overlay reads this
     * (alongside [isAvailable]) to tell "motion paused" apart from "no
     * gyroscope" in its motion indicator. Always false when [isAvailable] is
     * false, since [start] is then a no-op.
     */
    val isStreaming: Boolean get() = started

    // emit/started are handed between the main thread (start/stop) and the
    // sensor thread (callbacks); @Volatile makes that hand-off visible.
    @Volatile private var emit: Emit? = null

    @Volatile private var started = false

    // Display rotation (a Surface.ROTATION_* value) for the axis remap. Read
    // from rotationSupplier once per start() on the main thread, consumed on
    // the sensor thread — @Volatile publishes the start()-side write.
    @Volatile private var rotation: Int = DEFAULT_ROTATION

    // Latest accelerometer triple, pre-scaled to wire int16. Written on the
    // accel callback, read on the gyro callback — same (sensor) thread, so no
    // lock; @Volatile is only here so stop()'s main-thread reset is visible.
    @Volatile private var accelX: Short = 0

    @Volatile private var accelY: Short = 0

    @Volatile private var accelZ: Short = 0

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> onAccel(event.values)
                    Sensor.TYPE_GYROSCOPE -> onGyro(event.values)
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }

    /**
     * Begin streaming. No-op when the phone has no gyroscope, or when already
     * started. `SENSOR_DELAY_GAME` (~50 Hz–200 Hz depending on hardware) is
     * the standard low-latency rate; the [MotionRateLimiter] caps the wire
     * rate at 250 Hz regardless of how fast the sensor delivers.
     */
    fun start(emit: Emit) {
        if (started || gyro == null) return
        // Re-read the live display rotation on every (re)start so a rotation
        // between activity resumes is reflected in the axis remap.
        rotation = rotationSupplier()
        started = true
        this.emit = emit
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        accel?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.i(TAG, "Phone motion source started (gyro=${gyro.name}, accel=${accel?.name})")
    }

    /** Stop streaming and release the sensor listeners. Safe to call twice. */
    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(listener)
        emit = null
        rateLimiter.clearAll()
        accelX = 0
        accelY = 0
        accelZ = 0
    }

    private fun onAccel(values: FloatArray) {
        if (values.size < 3) return
        val (x, y, z) = MotionScaling.remapLandscape(values[0], values[1], values[2], rotation)
        accelX = MotionScaling.accelMssToWire(x)
        accelY = MotionScaling.accelMssToWire(y)
        accelZ = MotionScaling.accelMssToWire(z)
    }

    private fun onGyro(values: FloatArray) {
        if (values.size < 3) return
        val cb = emit ?: return
        val (x, y, z) = MotionScaling.remapLandscape(values[0], values[1], values[2], rotation)
        val sample =
            MotionRateLimiter.MotionSample(
                gyroX = MotionScaling.gyroRadToWire(x),
                gyroY = MotionScaling.gyroRadToWire(y),
                gyroZ = MotionScaling.gyroRadToWire(z),
                accelX = accelX,
                accelY = accelY,
                accelZ = accelZ,
            )
        // SINGLE_VIRTUAL_CONTROLLER: the touch overlay hosts exactly one
        // virtual controller, so the rate-limiter key is a constant.
        rateLimiter.publish(SINGLE_VIRTUAL_CONTROLLER, sample) { s, deltaUs ->
            cb.emit(s, deltaUs)
        }
    }

    private companion object {
        const val TAG = "PhoneMotionSource"
        const val SINGLE_VIRTUAL_CONTROLLER = 0

        // Surface.ROTATION_0 — fallback when no rotation supplier is provided
        // (e.g. in tests). Lands on the ROTATION_90 branch's sibling identity.
        const val DEFAULT_ROTATION = 0
    }
}
