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
        // The JVM stub reports SDK_INT 0, so the gate in hasGyro is what answers here: it must
        // never reach the per-device sensor read.
        assertTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        assertFalse(PhysicalMotionProbe.hasGyro(deviceId = 7))
    }

    @Test
    fun `returns false when the InputDevice is null`() {
        assertFalse(PhysicalMotionProbe.probeGyro(device = null))
    }

    @Test
    fun `returns false when the pad has no gyroscope sensor`() {
        assertFalse(PhysicalMotionProbe.probeGyro(device = deviceWithoutGyro()))
    }

    @Test
    fun `returns true when the pad reports a gyroscope`() {
        assertTrue(PhysicalMotionProbe.probeGyro(device = deviceWithGyro()))
    }
}
