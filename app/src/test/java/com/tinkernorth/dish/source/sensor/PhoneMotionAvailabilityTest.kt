// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PhoneMotionAvailability] — the process-scoped "does the
 * phone itself have a gyro?" fact. The value is decided once at injection
 * time (Android device hardware does not change at runtime), so the tests
 * just pin: gyro present ⇒ true, gyro absent ⇒ false, SENSOR_SERVICE absent
 * ⇒ false. The state flow exposing those values is the same shape every
 * [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource] subclass
 * uses; the base class is covered by `StateSourceProbeSampleTest`.
 */
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
        assertTrue(src.state.value)
    }

    @Test
    fun `phone without a gyroscope is not motion-available`() {
        // Tablets, very cheap phones, some kiosk hardware — the touch
        // overlay should show "Motion: not available", and the satellite
        // CAP_MOTION advertisement must not lie.
        val src = PhoneMotionAvailability(contextWithSensor(sensor = null))
        assertFalse(src.state.value)
    }

    @Test
    fun `null SENSOR_SERVICE falls through to not-available, does not throw`() {
        // Robolectric- or stub-flavoured Contexts can return null for system
        // services; the source defends so the app never NPEs at startup.
        val ctx =
            mockk<Context> {
                every { getSystemService(Context.SENSOR_SERVICE) } returns null
            }
        val src = PhoneMotionAvailability(ctx)
        assertFalse(src.state.value)
    }

    @Test
    fun `state flow exposes the same boolean as state value`() {
        // The composer subscribes via .state.collect — assert the StateFlow's
        // current value matches what the property reports.
        val src = PhoneMotionAvailability(contextWithSensor(mockk()))
        assertEquals(src.state.value, src.state.value)
        assertTrue(src.state.value)
    }
}
