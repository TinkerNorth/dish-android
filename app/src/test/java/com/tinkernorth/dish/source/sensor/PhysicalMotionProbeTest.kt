// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.view.InputDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalMotionProbeTest {
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
        assertFalse(PhysicalMotionProbe.evaluate(sdkInt = 30, device = deviceWithGyro()))
    }

    @Test
    fun `returns false when the InputDevice is null`() {
        assertFalse(PhysicalMotionProbe.evaluate(sdkInt = Build.VERSION_CODES.S, device = null))
    }

    @Test
    fun `returns false when the pad has no gyroscope sensor`() {
        assertFalse(
            PhysicalMotionProbe.evaluate(
                sdkInt = Build.VERSION_CODES.S,
                device = deviceWithoutGyro(),
            ),
        )
    }

    @Test
    fun `returns true when API 31+ and the pad reports a gyroscope`() {
        assertTrue(
            PhysicalMotionProbe.evaluate(
                sdkInt = Build.VERSION_CODES.S,
                device = deviceWithGyro(),
            ),
        )
    }

    @Test
    fun `is stable across higher SDK levels, does not regress past API 31`() {
        assertTrue(
            PhysicalMotionProbe.evaluate(
                sdkInt = Build.VERSION_CODES.S + 5,
                device = deviceWithGyro(),
            ),
        )
    }
}
