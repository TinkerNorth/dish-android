// SPDX-License-Identifier: LGPL-3.0-or-later

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

class PhoneMotionSourceTest {
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

    // SystemClock.elapsedRealtime() throws "not mocked" under JVM — inject a controllable counter.
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

        // 3-arg overload delivers on the main looper; the 4-arg overload pins this regression shut.
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
        assertEquals(1, dispatch.acquired)
    }

    @Test
    fun `stop unregisters the listener and releases the dispatch thread`() {
        val src = source()
        src.start { _, _ -> }
        src.stop()

        verify { sensorManager.unregisterListener(any<SensorEventListener>()) }
        assertEquals(1, dispatch.released)
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

        assertFalse(src.isAvailable)
        src.start { _, _ -> }
        assertFalse(src.isStreaming)
        assertEquals(0, dispatch.acquired)
        verify(exactly = 0) {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                any(),
                any(),
                any<Handler>(),
            )
        }
    }

    @Test
    fun `state is Disabled on a phone with no gyroscope, never flips on start`() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
        val src = source()

        assertEquals(MotionStreamState.Disabled, src.state.value)
        src.start { _, _ -> }
        assertEquals(MotionStreamState.Disabled, src.state.value)
    }

    @Test
    fun `state is Stopped on construction with a gyroscope, before start`() {
        val src = source()
        assertEquals(MotionStreamState.Stopped, src.state.value)
    }

    @Test
    fun `start flips state to Streaming optimistically`() {
        val src = source()
        src.start { _, _ -> }
        assertEquals(MotionStreamState.Streaming, src.state.value)
    }

    @Test
    fun `stop flips state back to Stopped, not Disabled`() {
        val src = source()
        src.start { _, _ -> }
        src.stop()
        assertEquals(MotionStreamState.Stopped, src.state.value)
    }

    @Test
    fun `first gyro callback before any accel is HELD until accel arrives`() {
        val emissions = mutableListOf<Pair<MotionRateLimiter.MotionSample, Int>>()
        val src = source()
        src.start { sample, dt -> emissions += sample to dt }

        src.onGyro(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(0, emissions.size)

        src.onAccel(floatArrayOf(0f, 9.80665f, 0f))
        src.onGyro(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(1, emissions.size)
        val emitted = emissions.single().first
        val nonZero = emitted.accelX.toInt() != 0 || emitted.accelY.toInt() != 0 || emitted.accelZ.toInt() != 0
        assertTrue(nonZero)
    }

    @Test
    fun `pad with NO accelerometer still streams gyro — gate does not block`() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns null
        val emissions = mutableListOf<Pair<MotionRateLimiter.MotionSample, Int>>()
        val src = source()
        src.start { sample, dt -> emissions += sample to dt }
        src.onGyro(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(1, emissions.size)
    }

    @Test
    fun `deriveState — no gyro present means Disabled regardless of started`() {
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
        // lastGyroMonoMs == 0L is a special-case Stalled regardless of window math — use non-zero anchor.
        assertEquals(
            MotionStreamState.Streaming,
            PhoneMotionSource
                .deriveState(
                    gyroPresent = true,
                    started = true,
                    lastGyroMonoMs = 0L,
                    nowMonoMs = PhoneMotionSource.STALL_WINDOW_MS,
                ).let {
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
