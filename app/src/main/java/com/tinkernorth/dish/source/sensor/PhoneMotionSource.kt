// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource

class PhoneMotionSource(
    private val sensorManager: SensorManager,
    private val rotationSupplier: () -> Int = { DEFAULT_ROTATION },
    private val rateLimiter: MotionRateLimiter = MotionRateLimiter(),
    private val sensorDispatch: SensorDispatch = HandlerThreadSensorDispatch("PhoneMotionSensor"),
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : AbstractStateSource<MotionStreamState>(MotionStreamState.Disabled) {
    fun interface Emit {
        fun emit(
            sample: MotionRateLimiter.MotionSample,
            timestampDeltaUs: Int,
        )
    }

    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    init {
        setState(if (gyro == null) MotionStreamState.Disabled else MotionStreamState.Stopped)
    }

    val isAvailable: Boolean get() = gyro != null

    val isStreaming: Boolean get() = started

    val isStalled: Boolean get() = state.value == MotionStreamState.Stalled

    @Volatile private var lastGyroMonoMs: Long = 0L

    @Volatile private var emit: Emit? = null

    @Volatile private var started = false

    @Volatile private var accelX: Short = 0

    @Volatile private var accelY: Short = 0

    @Volatile private var accelZ: Short = 0

    // Gate first gyro emission until accel reports; otherwise wire ships (0,0,0) read as zero-gravity.
    @Volatile private var accelSeen: Boolean = false

    @Volatile private var stallTickHandler: android.os.Handler? = null

    @Volatile private var stallTickRunnable: Runnable? = null

    private val remapScratch = FloatArray(3)

    private val loggedUnknownRotations = HashSet<Int>()

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

    fun start(emit: Emit) {
        if (started || gyro == null) return
        started = true
        this.emit = emit
        // 4-arg registerListener moves callbacks off the UI thread.
        val handler = sensorDispatch.acquire()
        stallTickHandler = handler
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME, handler)
        accel?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        setState(MotionStreamState.Streaming)
        scheduleStallTick(handler)
        Log.i(TAG, "Phone motion source started (gyro=${gyro.name}, accel=${accel?.name})")
    }

    fun stop() {
        if (!started) return
        started = false
        // Cancel tick BEFORE releasing the handler thread it posts to.
        stallTickRunnable?.let { stallTickHandler?.removeCallbacks(it) }
        stallTickRunnable = null
        stallTickHandler = null
        sensorManager.unregisterListener(listener)
        sensorDispatch.release()
        emit = null
        rateLimiter.clearAll()
        accelX = 0
        accelY = 0
        accelZ = 0
        accelSeen = false
        lastGyroMonoMs = 0L
        setState(if (gyro == null) MotionStreamState.Disabled else MotionStreamState.Stopped)
    }

    internal fun onAccel(values: FloatArray) {
        if (values.size < 3) return
        // Re-read rotation per sample: activity configChanges swallow landscape flips.
        val rotation = rotationSupplier()
        val result = MotionScaling.remapLandscape(values[0], values[1], values[2], rotation, remapScratch)
        if (result is MotionScaling.RemapResult.Fallback) onUnknownRotation(result.unknownRotation)
        accelX = MotionScaling.accelMssToWire(remapScratch[0])
        accelY = MotionScaling.accelMssToWire(remapScratch[1])
        accelZ = MotionScaling.accelMssToWire(remapScratch[2])
        accelSeen = true
    }

    internal fun onGyro(values: FloatArray) {
        if (values.size < 3) return
        if (accel != null && !accelSeen) return
        lastGyroMonoMs = nowMs()
        if (state.value == MotionStreamState.Stalled) {
            setState(MotionStreamState.Streaming)
        }
        val cb = emit ?: return
        val rotation = rotationSupplier()
        val result = MotionScaling.remapLandscape(values[0], values[1], values[2], rotation, remapScratch)
        if (result is MotionScaling.RemapResult.Fallback) onUnknownRotation(result.unknownRotation)
        val sample =
            MotionRateLimiter.MotionSample(
                gyroX = MotionScaling.gyroRadToWire(remapScratch[0]),
                gyroY = MotionScaling.gyroRadToWire(remapScratch[1]),
                gyroZ = MotionScaling.gyroRadToWire(remapScratch[2]),
                accelX = accelX,
                accelY = accelY,
                accelZ = accelZ,
            )
        rateLimiter.publish(SINGLE_VIRTUAL_CONTROLLER, sample) { s, deltaUs ->
            cb.emit(s, deltaUs)
        }
    }

    private fun onUnknownRotation(rotation: Int) {
        if (loggedUnknownRotations.add(rotation)) {
            Log.w(
                TAG,
                "remapLandscape: unknown rotation=$rotation, falling back to ROTATION_90 remap",
            )
        }
    }

    private fun scheduleStallTick(handler: android.os.Handler) {
        val tick =
            Runnable {
                if (!started) return@Runnable
                val gap = nowMs() - lastGyroMonoMs
                val next =
                    if (lastGyroMonoMs == 0L || gap > STALL_WINDOW_MS) {
                        MotionStreamState.Stalled
                    } else {
                        MotionStreamState.Streaming
                    }
                if (state.value != next) setState(next)
                scheduleStallTick(handler)
            }
        stallTickRunnable = tick
        handler.postDelayed(tick, STALL_TICK_MS)
    }

    companion object {
        private const val TAG = "PhoneMotionSource"
        const val SINGLE_VIRTUAL_CONTROLLER = 0

        const val DEFAULT_ROTATION = 0

        const val STALL_WINDOW_MS = 1500L

        const val STALL_TICK_MS = 500L

        internal fun deriveState(
            gyroPresent: Boolean,
            started: Boolean,
            lastGyroMonoMs: Long,
            nowMonoMs: Long,
        ): MotionStreamState =
            when {
                !gyroPresent -> MotionStreamState.Disabled
                !started -> MotionStreamState.Stopped
                lastGyroMonoMs == 0L -> MotionStreamState.Stalled
                nowMonoMs - lastGyroMonoMs > STALL_WINDOW_MS -> MotionStreamState.Stalled
                else -> MotionStreamState.Streaming
            }
    }
}

enum class MotionStreamState { Disabled, Stopped, Streaming, Stalled }
