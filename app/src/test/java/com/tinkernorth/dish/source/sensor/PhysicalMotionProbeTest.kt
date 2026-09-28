// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.view.InputDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhysicalMotionProbeTest {
    // hasGyro looks the pad up through the static, so that is what the API-gate tests stand in for.
    @Before
    fun setUp() {
        mockkStatic(InputDevice::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(InputDevice::class)
    }

    private val gyro: Sensor = mockk(relaxed = true)

    private fun deviceWithGyro(): InputDevice {
        val sm =
            mockk<SensorManager> {
                every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyro
            }
        return mockk { every { sensorManager } returns sm }
    }

    private fun deviceWithoutGyro(): InputDevice {
        val sm =
            mockk<SensorManager> {
                every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
            }
        return mockk { every { sensorManager } returns sm }
    }

    @Test
    fun `returns false on API below 31 - per-device sensor API does not exist`() {
        // The JVM stub reports SDK_INT 0, so the gate in hasGyro is what answers here: it must
        // never reach the per-device sensor read.
        assertTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        assertFalse(hasGyro(deviceId = 7))
    }

    @Test
    fun `below API 31 the pad is never looked up`() {
        assertFalse(hasGyro(deviceId = 7, sdkInt = Build.VERSION_CODES.R))
        verify(exactly = 0) { InputDevice.getDevice(any()) }
    }

    @Test
    fun `from API 31 a pad reporting a gyroscope has one`() {
        val pad = deviceWithGyro()
        every { InputDevice.getDevice(7) } returns pad

        assertTrue(hasGyro(deviceId = 7, sdkInt = Build.VERSION_CODES.S))
    }

    @Test
    fun `from API 31 a pad without a gyroscope has none`() {
        val pad = deviceWithoutGyro()
        every { InputDevice.getDevice(7) } returns pad

        assertFalse(hasGyro(deviceId = 7, sdkInt = Build.VERSION_CODES.S))
    }

    @Test
    fun `from API 31 a pad that is already gone has none`() {
        every { InputDevice.getDevice(7) } returns null

        assertFalse(hasGyro(deviceId = 7, sdkInt = Build.VERSION_CODES.S))
    }

    @Test
    fun `returns false when the InputDevice is null`() {
        assertFalse(probeGyro(device = null))
    }

    @Test
    fun `returns false when the pad has no gyroscope sensor`() {
        assertFalse(probeGyro(device = deviceWithoutGyro()))
    }

    @Test
    fun `returns true when the pad reports a gyroscope`() {
        assertTrue(probeGyro(device = deviceWithGyro()))
    }
}
