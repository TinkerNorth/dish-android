// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PhoneMotionSource], focused on the off-main-thread sensor
 * registration contract.
 *
 * `SensorManager.registerListener(listener, sensor, delay)` — the 3-arg
 * overload — delivers callbacks on the **main looper**, which would run the
 * scale → rate-limit → encrypt → UDP-send pipeline on the UI thread at the
 * sensor's native rate. The fix registers with the 4-arg overload and a
 * [SensorDispatch]-supplied [Handler] on a dedicated thread. A regression
 * back to the 3-arg overload fails
 * [start registers sensors on the dispatch Handler, not the bare overload].
 */
class PhoneMotionSourceTest {
    /** A [SensorDispatch] that hands out a mock Handler and counts acquire/release. */
    private class RecordingSensorDispatch : SensorDispatch {
        val handler: Handler = mockk(relaxed = true)
        var acquired = 0
            private set
        var released = 0
            private set

        override fun acquire(): Handler {
            acquired++
            return handler
        }

        override fun release() {
            released++
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var gyro: Sensor
    private lateinit var accel: Sensor
    private lateinit var dispatch: RecordingSensorDispatch

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        gyro = mockk { every { name } returns "fake-gyro" }
        accel = mockk { every { name } returns "fake-accel" }
        sensorManager =
            mockk {
                every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyro
                every { getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns accel
                every {
                    registerListener(any<SensorEventListener>(), any(), any(), any<Handler>())
                } returns true
                every { unregisterListener(any<SensorEventListener>()) } just Runs
            }
        dispatch = RecordingSensorDispatch()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /**
     * Fake monotonic clock — JVM tests don't have a real Android
     * SystemClock, so the production default `{ SystemClock.elapsedRealtime() }`
     * throws "not mocked." Inject a controllable counter so each test
     * can drive time deterministically.
     */
    private var fakeNowMs: Long = 1000L

    private fun source() =
        PhoneMotionSource(
            sensorManager = sensorManager,
            rotationSupplier = { 0 },
            rateLimiter = MotionRateLimiter(),
            sensorDispatch = dispatch,
            nowMs = { fakeNowMs },
        )

    @Test
    fun `start registers sensors on the dispatch Handler, not the bare overload`() {
        source().start { _, _ -> }

        // The off-main contract: registered via the 4-arg overload with the
        // dispatch thread's Handler. The 3-arg overload delivers callbacks on
        // the main looper — the bug this test pins shut.
        verify(exactly = 1) {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                gyro,
                SensorManager.SENSOR_DELAY_GAME,
                dispatch.handler,
            )
        }
        verify(exactly = 1) {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                accel,
                SensorManager.SENSOR_DELAY_GAME,
                dispatch.handler,
            )
        }
        assertEquals("the dispatch thread is acquired exactly once", 1, dispatch.acquired)
    }

    @Test
    fun `stop unregisters the listener and releases the dispatch thread`() {
        val src = source()
        src.start { _, _ -> }
        src.stop()

        verify { sensorManager.unregisterListener(any<SensorEventListener>()) }
        assertEquals("the dispatch thread must be released so it does not leak", 1, dispatch.released)
    }

    @Test
    fun `isStreaming tracks start and stop`() {
        val src = source()
        assertFalse(src.isStreaming)
        src.start { _, _ -> }
        assertTrue(src.isStreaming)
        src.stop()
        assertFalse(src.isStreaming)
    }

    @Test
    fun `a redundant start does not register twice or acquire a second thread`() {
        val src = source()
        src.start { _, _ -> }
        src.start { _, _ -> }

        assertEquals(1, dispatch.acquired)
        verify(exactly = 1) {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                gyro,
                any(),
                any<Handler>(),
            )
        }
    }

    @Test
    fun `with no gyroscope start is a no-op and acquires no dispatch thread`() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
        val src = source()

        assertFalse("a phone with no gyroscope is not motion-available", src.isAvailable)
        src.start { _, _ -> }
        assertFalse(src.isStreaming)
        assertEquals("no gyroscope → no sensor thread", 0, dispatch.acquired)
        verify(exactly = 0) {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                any(),
                any(),
                any<Handler>(),
            )
        }
    }

    // ── MotionStreamState flow (PR4) ─────────────────────────────────────

    @Test
    fun `state is Disabled on a phone with no gyroscope, never flips on start`() {
        // Hardware decision is permanent — the pill must read UNAVAILABLE
        // even if the activity later calls start (which is a no-op).
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
        val src = source()

        assertEquals(MotionStreamState.Disabled, src.state.value)
        src.start { _, _ -> }
        assertEquals(MotionStreamState.Disabled, src.state.value)
    }

    @Test
    fun `state is Stopped on construction with a gyroscope, before start`() {
        // Before the overlay calls start, the source has a gyro but isn't
        // streaming. The pill must NOT read STREAMING; it should read PAUSED
        // (mapped from Stopped + connected) or NOT_FORWARDED (BT-HID kind).
        val src = source()
        assertEquals(MotionStreamState.Stopped, src.state.value)
    }

    @Test
    fun `start flips state to Streaming optimistically`() {
        // The optimistic flip — the first stall tick re-evaluates after
        // STALL_WINDOW_MS, but we want the pill to read STREAMING the
        // instant the source starts so the user gets feedback.
        val src = source()
        src.start { _, _ -> }
        assertEquals(MotionStreamState.Streaming, src.state.value)
    }

    @Test
    fun `stop flips state back to Stopped, not Disabled`() {
        // The hardware decision (Disabled vs Stopped) is permanent for the
        // lifetime of the source. A stop after a start must not lose that
        // distinction — stop on a gyro-equipped phone must read Stopped.
        val src = source()
        src.start { _, _ -> }
        src.stop()
        assertEquals(MotionStreamState.Stopped, src.state.value)
    }

    // ── First-sample accel-zero race (PR5) ───────────────────────────────

    @Test
    fun `first gyro callback before any accel is HELD until accel arrives`() {
        // The bug this pins: the first MOTION packet on the wire previously
        // shipped accel = (0, 0, 0) because the gyro callback fired before
        // any accel callback. Hold the first gyro until accel reports, so
        // downstream consumers don't read "stationary in zero gravity" as
        // the opening frame. The internal onAccel/onGyro entry points are
        // exposed for this test — see their KDoc.
        val emissions = mutableListOf<Pair<MotionRateLimiter.MotionSample, Int>>()
        val src = source()
        src.start { sample, dt -> emissions += sample to dt }

        // Fire ONLY a gyro callback first — the gate must drop it.
        src.onGyro(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals("first gyro alone must not emit", 0, emissions.size)

        // Now an accel arrives, followed by another gyro — the second gyro
        // should emit a sample with the now-real accel cache.
        src.onAccel(floatArrayOf(0f, 9.80665f, 0f))
        src.onGyro(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals("emit after accel has reported", 1, emissions.size)
        // The emitted accel triple is non-zero (1g along the remapped axis,
        // scaled to wire int16). Strictly ≠ 0 on at least one axis pins
        // that we are NOT shipping the stale-zero cache.
        val emitted = emissions.single().first
        val nonZero = emitted.accelX.toInt() != 0 || emitted.accelY.toInt() != 0 || emitted.accelZ.toInt() != 0
        assertTrue("emitted accel must be non-zero", nonZero)
    }

    @Test
    fun `pad with NO accelerometer still streams gyro — gate does not block`() {
        // A device that reports a gyro but no accel (rare, but the test
        // matrix must cover it). [accel] is null at construct time; the
        // gate must short-circuit so gyro samples still flow.
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns null
        val emissions = mutableListOf<Pair<MotionRateLimiter.MotionSample, Int>>()
        val src = source()
        src.start { sample, dt -> emissions += sample to dt }
        src.onGyro(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals("gyro must emit even with no accel sensor", 1, emissions.size)
    }

    // ── deriveState — pure decision table ────────────────────────────────

    @Test
    fun `deriveState — no gyro present means Disabled regardless of started`() {
        // Hardware permanence: no gyro ⇒ Disabled even if started is
        // somehow true (defensive — the start() path defends, but the
        // pure decider must too).
        assertEquals(
            MotionStreamState.Disabled,
            PhoneMotionSource.deriveState(
                gyroPresent = false,
                started = false,
                lastGyroMonoMs = 0L,
                nowMonoMs = 0L,
            ),
        )
        assertEquals(
            MotionStreamState.Disabled,
            PhoneMotionSource.deriveState(
                gyroPresent = false,
                started = true,
                lastGyroMonoMs = 10_000L,
                nowMonoMs = 10_000L,
            ),
        )
    }

    @Test
    fun `deriveState — gyro present but not started means Stopped`() {
        assertEquals(
            MotionStreamState.Stopped,
            PhoneMotionSource.deriveState(
                gyroPresent = true,
                started = false,
                lastGyroMonoMs = 0L,
                nowMonoMs = 1000L,
            ),
        )
    }

    @Test
    fun `deriveState — started with no gyro sample yet means Stalled`() {
        // lastGyroMonoMs == 0L means "no sample ever arrived." Should
        // surface as Stalled so the user gets a "is the sensor broken?"
        // hint quickly, not "STREAMING" silently.
        assertEquals(
            MotionStreamState.Stalled,
            PhoneMotionSource.deriveState(
                gyroPresent = true,
                started = true,
                lastGyroMonoMs = 0L,
                nowMonoMs = 1000L,
            ),
        )
    }

    @Test
    fun `deriveState — last gyro within window means Streaming`() {
        // 200 ms after the last sample, well inside the 1500 ms window.
        assertEquals(
            MotionStreamState.Streaming,
            PhoneMotionSource.deriveState(
                gyroPresent = true,
                started = true,
                lastGyroMonoMs = 800L,
                nowMonoMs = 1000L,
            ),
        )
    }

    @Test
    fun `deriveState — last gyro past stall window means Stalled`() {
        // 2000 ms after the last sample, > 1500 ms window. The OEM-pauses-
        // sensors case the original isStalled comment calls out.
        assertEquals(
            MotionStreamState.Stalled,
            PhoneMotionSource.deriveState(
                gyroPresent = true,
                started = true,
                lastGyroMonoMs = 1000L,
                nowMonoMs = 3000L,
            ),
        )
    }

    @Test
    fun `deriveState — exactly at window boundary is still Streaming`() {
        // STALL_WINDOW_MS = 1500 ms; gap of exactly 1500 ms should be the
        // last value that reads Streaming (strict greater-than for Stalled).
        // A regression that uses >= would flip prematurely on every device.
        assertEquals(
            MotionStreamState.Streaming,
            PhoneMotionSource.deriveState(
                gyroPresent = true,
                started = true,
                lastGyroMonoMs = 0L,
                nowMonoMs = PhoneMotionSource.STALL_WINDOW_MS,
            ).let {
                // Special-case: deriveState treats lastGyroMonoMs == 0 as
                // Stalled regardless of the window math. To exercise the
                // strict-greater path, use a non-zero anchor.
                PhoneMotionSource.deriveState(
                    gyroPresent = true,
                    started = true,
                    lastGyroMonoMs = 1L,
                    nowMonoMs = 1L + PhoneMotionSource.STALL_WINDOW_MS,
                )
            },
        )
    }
}
