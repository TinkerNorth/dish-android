// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource

/**
 * Captures the phone's gyroscope + accelerometer and forwards IMU samples
 * for the on-screen touch controller (`GamepadOverlayActivity`).
 *
 * **State-flow contract (PR4):** this source is an
 * [AbstractStateSource]`<`[MotionStreamState]`>`. The activity-side pill
 * reads `state.collect { … }` rather than polling `isStreaming` /
 * `isStalled` from the UI, so a stalled gyro (sensor exists, no callbacks
 * arriving) demotes the pill to STALLED on its own tick — previously the
 * pill stayed on STREAMING forever unless something *else* triggered a
 * repaint. See the [STALL_WINDOW_MS] / [STALL_TICK_MS] companion vals for
 * the cadence.
 *
 * This is the marquee Android motion use case from the roadmap (Task 1.1):
 * a phone in touch-overlay mode becomes a motion source — gyro aim for
 * shooters, tilt for emulators. Physical gamepads that expose an IMU
 * through the Android `InputDevice` API are a separate path
 * ([PhysicalMotionSource]).
 *
 * Pipeline: `SensorManager` (rad/s, m/s², device frame) → [MotionScaling]
 * (DSU int16, screen frame) → [MotionRateLimiter] (≤ 250 Hz gate) → [Emit].
 *
 * The accelerometer and gyroscope arrive on separate callbacks; we cache the
 * latest accel triple and emit a fused sample on each gyro tick (gyro is the
 * higher-rate, latency-critical signal). Both callbacks run on the same
 * [SensorDispatch] thread, so the accel cache itself needs no lock.
 *
 * Threading: `SensorManager`'s 3-arg `registerListener` delivers callbacks on
 * the **main looper**, which would run the whole scale → rate-limit → encrypt
 * → UDP-send pipeline on the UI thread at the sensor's native rate. So this
 * source registers via the 4-arg overload with a [SensorDispatch] `Handler`
 * (a dedicated background thread) instead. [start]/[stop] still run on the
 * caller's (main) thread, so the fields touched by both — [emit], the
 * [accelX]/[accelY]/[accelZ] cache that [stop] clears — are `@Volatile` for
 * that cross-thread hand-off.
 *
 * [rotationSupplier] is queried **per sample** (cheaply cached for the gyro +
 * accel ticks of one fused frame). `GamepadOverlayActivity` declares
 * `configChanges="orientation|screenSize"`, so flipping the phone end-over-end
 * (ROTATION_90 ↔ ROTATION_270) does NOT recreate the activity and would never
 * reach a once-per-`start()` read — the IMU axes would be left 180° sideways
 * for the rest of the session. Re-reading per sample keeps the axis remap
 * correct through a live landscape flip; `Display.getRotation()` is a cheap
 * local read, not an IPC, so this costs nothing measurable on the hot path.
 */
class PhoneMotionSource(
    private val sensorManager: SensorManager,
    private val rotationSupplier: () -> Int = { DEFAULT_ROTATION },
    private val rateLimiter: MotionRateLimiter = MotionRateLimiter(),
    private val sensorDispatch: SensorDispatch = HandlerThreadSensorDispatch("PhoneMotionSensor"),
    /**
     * Monotonic clock source, in milliseconds. Overridable for tests so the
     * stall-detector's time math can be driven deterministically without
     * waiting real wall-clock ms. Defaults to [android.os.SystemClock
     * .elapsedRealtime] in production.
     */
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : AbstractStateSource<MotionStreamState>(MotionStreamState.Disabled) {
    /** Invoked when a fused sample passes the rate-limit gate. */
    fun interface Emit {
        fun emit(
            sample: MotionRateLimiter.MotionSample,
            timestampDeltaUs: Int,
        )
    }

    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    init {
        // Seed initial state from hardware availability — Disabled when no
        // gyro, Stopped otherwise. The pill reads this at construct time so
        // the "no gyroscope" tile is visible before the first resume.
        setState(if (gyro == null) MotionStreamState.Disabled else MotionStreamState.Stopped)
    }

    /**
     * True when the phone has a gyroscope. Backwards-compat shim for
     * pre-PR4 call sites; new code should subscribe to [state] instead and
     * inspect `MotionStreamState.Disabled`.
     */
    val isAvailable: Boolean get() = gyro != null

    /**
     * True between [start] and [stop] — i.e. the sensor listeners are
     * registered and gyro samples are being forwarded. Backwards-compat
     * shim; new code reads [state] for Streaming/Stalled distinction.
     */
    val isStreaming: Boolean get() = started

    /**
     * True when the source is started but no gyro sample has arrived
     * recently (within [STALL_WINDOW_MS]). Backwards-compat shim; new code
     * reads [state] and checks `MotionStreamState.Stalled` directly.
     */
    val isStalled: Boolean get() = state.value == MotionStreamState.Stalled

    /** Monotonic ms of the most recent gyro callback. Used by stall detection. */
    @Volatile private var lastGyroMonoMs: Long = 0L

    // emit/started are handed between the main thread (start/stop) and the
    // SensorDispatch thread (callbacks); @Volatile makes that hand-off visible.
    @Volatile private var emit: Emit? = null

    @Volatile private var started = false

    // Latest accelerometer triple, pre-scaled to wire int16. Written on the
    // accel callback, read on the gyro callback — same dispatch thread, so no
    // lock; @Volatile is only here so stop()'s main-thread reset is visible.
    @Volatile private var accelX: Short = 0

    @Volatile private var accelY: Short = 0

    @Volatile private var accelZ: Short = 0

    /**
     * Handler the stall tick is posted on. Set in [start] from the dispatch
     * thread's Handler so the tick lands on the same thread the gyro
     * callbacks do, avoiding a needless cross-thread state read. Cleared in
     * [stop].
     */
    @Volatile private var stallTickHandler: android.os.Handler? = null

    /** The currently-scheduled stall tick, kept so [stop] can cancel it. */
    @Volatile private var stallTickRunnable: Runnable? = null

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
        started = true
        this.emit = emit
        // 4-arg registerListener with a dedicated-thread Handler. The 3-arg
        // overload delivers callbacks on the main looper, which would put the
        // encrypt + UDP-send pipeline on the UI thread (see the class KDoc).
        val handler = sensorDispatch.acquire()
        stallTickHandler = handler
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME, handler)
        accel?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        // Optimistic initial state: assume samples will arrive. The first
        // stall tick re-evaluates after STALL_WINDOW_MS, demoting to
        // Stalled if no gyro callback landed.
        setState(MotionStreamState.Streaming)
        scheduleStallTick(handler)
        Log.i(TAG, "Phone motion source started (gyro=${gyro.name}, accel=${accel?.name})")
    }

    /** Stop streaming and release the sensor listeners + dispatch thread. Safe to call twice. */
    fun stop() {
        if (!started) return
        started = false
        // Cancel the stall tick BEFORE releasing the handler — otherwise the
        // tick could run between removeCallbacks and release, posting again
        // onto a thread that's about to disappear.
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
        lastGyroMonoMs = 0L
        setState(if (gyro == null) MotionStreamState.Disabled else MotionStreamState.Stopped)
    }

    private fun onAccel(values: FloatArray) {
        if (values.size < 3) return
        // Re-read the live rotation per sample: a runtime landscape flip is
        // swallowed by the activity's configChanges, so a once-per-start read
        // would leave the remap stale (see the class KDoc).
        val (x, y, z) =
            MotionScaling.remapLandscape(values[0], values[1], values[2], rotationSupplier())
        accelX = MotionScaling.accelMssToWire(x)
        accelY = MotionScaling.accelMssToWire(y)
        accelZ = MotionScaling.accelMssToWire(z)
    }

    private fun onGyro(values: FloatArray) {
        if (values.size < 3) return
        lastGyroMonoMs = nowMs()
        // Optimistic: every gyro callback proves we're streaming, so a
        // momentary Stalled flips back to Streaming without waiting for the
        // next tick. Cheap — same dispatch thread, no scheduler hop.
        if (state.value == MotionStreamState.Stalled) {
            setState(MotionStreamState.Streaming)
        }
        val cb = emit ?: return
        val (x, y, z) =
            MotionScaling.remapLandscape(values[0], values[1], values[2], rotationSupplier())
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

    /**
     * Schedule the next stall-detection tick on [handler]. The tick reads
     * `nowMs() - lastGyroMonoMs` and demotes [MotionStreamState.Streaming]
     * to [MotionStreamState.Stalled] when the gap exceeds [STALL_WINDOW_MS]
     * — recovering on the next gyro callback (see [onGyro]).
     *
     * The tick re-arms itself while [started] so the pill keeps tracking
     * state-over-time without a separate timer in the UI layer.
     */
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
                // Re-arm. The tick is captured in a local then stashed on
                // the field so [stop] can cancel it; a fresh Runnable each
                // iteration is fine — no allocation budget on a 500 ms
                // cadence.
                scheduleStallTick(handler)
            }
        stallTickRunnable = tick
        handler.postDelayed(tick, STALL_TICK_MS)
    }

    companion object {
        private const val TAG = "PhoneMotionSource"
        const val SINGLE_VIRTUAL_CONTROLLER = 0

        // Surface.ROTATION_0 — fallback when no rotation supplier is provided
        // (e.g. in tests); the identity remap in MotionScaling.remapLandscape.
        const val DEFAULT_ROTATION = 0

        /**
         * If the gyro hasn't fired for longer than this, treat the source
         * as stalled. SENSOR_DELAY_GAME is ~20ms; 1.5s is enough to absorb
         * a sensor pause / coalesce without overreacting, while still
         * surfacing a "stalled" signal before the user wastes a level
         * worth of input.
         */
        const val STALL_WINDOW_MS = 1500L

        /**
         * Cadence at which the stall detector re-evaluates. 500 ms is the
         * sweet spot — fast enough that a stall surfaces within ~2 s of
         * onset (one window + one tick), slow enough that the periodic
         * wake on the sensor dispatch thread is a non-event for battery.
         */
        const val STALL_TICK_MS = 500L

        /**
         * Pure decision: given the four facts the source tracks, return
         * the corresponding [MotionStreamState]. Lifted out so the matrix
         * can be pinned by a JVM unit test without driving a real
         * Handler-scheduled tick.
         */
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

/**
 * UI-relevant phases of [PhoneMotionSource]:
 *
 *  - [Disabled] — the phone has no gyroscope; [PhoneMotionSource.start]
 *    is a no-op for the lifetime of the process. The pill reads
 *    UNAVAILABLE.
 *  - [Stopped] — gyro present but the source is not started (overlay
 *    backgrounded, or capability gate says off). The pill reads PAUSED.
 *  - [Streaming] — gyro present, started, samples arriving recently.
 *    The pill reads STREAMING (or NOT_FORWARDED if the connection kind
 *    is Bluetooth, which is a *connection* property, not a source one).
 *  - [Stalled] — gyro present, started, but no callback in
 *    [PhoneMotionSource.STALL_WINDOW_MS]. The pill reads STALLED. Recovers
 *    automatically on the next gyro callback.
 */
enum class MotionStreamState { Disabled, Stopped, Streaming, Stalled }
