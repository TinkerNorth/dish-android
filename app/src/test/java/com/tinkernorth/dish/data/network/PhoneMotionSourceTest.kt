// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

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

    private fun source() =
        PhoneMotionSource(
            sensorManager = sensorManager,
            rotationSupplier = { 0 },
            rateLimiter = MotionRateLimiter(),
            sensorDispatch = dispatch,
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
}
