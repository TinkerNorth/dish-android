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
 * higher-rate, latency-critical signal). Both listeners run on the same
 * sensor thread, so the cache needs no extra synchronisation.
 */
class PhoneMotionSource(
    private val sensorManager: SensorManager,
    private val rateLimiter: MotionRateLimiter = MotionRateLimiter(),
) {
    /** Invoked when a fused sample passes the rate-limit gate. */
    fun interface Emit {
        fun emit(sample: MotionRateLimiter.MotionSample, timestampDeltaUs: Int)
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

    private var emit: Emit? = null
    private var started = false

    // Latest accelerometer triple, pre-scaled to wire int16. Written on the
    // accel callback, read on the gyro callback — same thread, no lock.
    private var accelX: Short = 0
    private var accelY: Short = 0
    private var accelZ: Short = 0

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> onAccel(event.values)
                    Sensor.TYPE_GYROSCOPE -> onGyro(event.values)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

    /**
     * Begin streaming. No-op when the phone has no gyroscope, or when already
     * started. `SENSOR_DELAY_GAME` (~50 Hz–200 Hz depending on hardware) is
     * the standard low-latency rate; the [MotionRateLimiter] caps the wire
     * rate at 250 Hz regardless of how fast the sensor delivers.
     */
    fun start(emit: Emit) {
        if (started || gyro == null) return
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
        val (x, y, z) = MotionScaling.remapLandscape(values[0], values[1], values[2])
        accelX = MotionScaling.accelMssToWire(x)
        accelY = MotionScaling.accelMssToWire(y)
        accelZ = MotionScaling.accelMssToWire(z)
    }

    private fun onGyro(values: FloatArray) {
        if (values.size < 3) return
        val cb = emit ?: return
        val (x, y, z) = MotionScaling.remapLandscape(values[0], values[1], values[2])
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
    }
}
