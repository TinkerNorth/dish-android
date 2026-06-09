// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMotionAvailabilityTest {
    private fun contextWithSensor(sensor: Sensor?): Context {
        val sm =
            mockk<SensorManager> {
                every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns sensor
            }
        return mockk { every { getSystemService(Context.SENSOR_SERVICE) } returns sm }
    }

    @Test
    fun `phone with a gyroscope is motion-available`() {
        val src = PhoneMotionAvailability(contextWithSensor(mockk()))
        assertTrue(src.hasGyro)
    }

    @Test
    fun `phone without a gyroscope is not motion-available`() {
        val src = PhoneMotionAvailability(contextWithSensor(sensor = null))
        assertFalse(src.hasGyro)
    }

    @Test
    fun `null SENSOR_SERVICE falls through to not-available, does not throw`() {
        val ctx =
            mockk<Context> {
                every { getSystemService(Context.SENSOR_SERVICE) } returns null
            }
        val src = PhoneMotionAvailability(ctx)
        assertFalse(src.hasGyro)
    }
}
