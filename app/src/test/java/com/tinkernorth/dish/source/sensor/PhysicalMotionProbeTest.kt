// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

/**
 * Unit tests for [PhysicalMotionProbe.evaluate] — the pure decision exercised
 * directly. The thin `hasGyro(deviceId)` wrapper is just `evaluate(SDK_INT,
 * InputDevice.getDevice(id))`, so pinning the four cases here covers both.
 *
 * `Build.VERSION_CODES.S` is referenced as the threshold; the test makes both
 * sides of that threshold explicit so a future API bump that nudges the
 * constant is still caught.
 */
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
    fun `returns false on API below 31 — per-device sensor API does not exist`() {
        // Even a pad with a real IMU can't be reached pre-API 31, so the
        // probe must say no. Use SDK_INT - 1 (one below S) explicitly.
        assertFalse(PhysicalMotionProbe.evaluate(sdkInt = 30, device = deviceWithGyro()))
    }

    @Test
    fun `returns false when the InputDevice is null`() {
        // A device that was unplugged between the InputManager callback and
        // the probe call must resolve to "no gyro" without throwing.
        assertFalse(PhysicalMotionProbe.evaluate(sdkInt = Build.VERSION_CODES.S, device = null))
    }

    @Test
    fun `returns false when the pad has no gyroscope sensor`() {
        // A cheap Bluetooth pad on API 31+ — the per-device SensorManager
        // exists but reports no TYPE_GYROSCOPE.
        assertFalse(
            PhysicalMotionProbe.evaluate(
                sdkInt = Build.VERSION_CODES.S,
                device = deviceWithoutGyro(),
            ),
        )
    }

    @Test
    fun `returns true when API 31+ and the pad reports a gyroscope`() {
        // The success path — DualSense or similar with an IMU.
        assertTrue(
            PhysicalMotionProbe.evaluate(
                sdkInt = Build.VERSION_CODES.S,
                device = deviceWithGyro(),
            ),
        )
    }

    @Test
    fun `is stable across higher SDK levels — does not regress past API 31`() {
        // Future API bumps must not silently turn the gate back off; pin a
        // higher SDK level too so a typo in the threshold check is caught.
        assertTrue(
            PhysicalMotionProbe.evaluate(
                sdkInt = Build.VERSION_CODES.S + 5,
                device = deviceWithGyro(),
            ),
        )
    }
}
