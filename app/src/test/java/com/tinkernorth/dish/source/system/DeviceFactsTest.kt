// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The phone facts the bundle carries, read from what the platform reports.
class DeviceFactsTest {
    @Test
    fun `no throttling is nominal`() {
        assertEquals("nominal", thermalWire(PowerManager.THERMAL_STATUS_NONE))
    }

    @Test
    fun `light and moderate throttling are fair`() {
        assertEquals("fair", thermalWire(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals("fair", thermalWire(PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test
    fun `severe throttling is serious`() {
        assertEquals("serious", thermalWire(PowerManager.THERMAL_STATUS_SEVERE))
    }

    @Test
    fun `critical, emergency and shutdown are critical`() {
        assertEquals("critical", thermalWire(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals("critical", thermalWire(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals("critical", thermalWire(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `a status the platform does not name is unknown`() {
        assertNull(thermalWire(UNNAMED_STATUS))
    }

    @Test
    fun `the battery level is a percent of the scale it was given in`() {
        assertEquals(81, batteryPercentOf(level = 81, scale = 100))
        assertEquals(50, batteryPercentOf(level = 128, scale = 256))
    }

    @Test
    fun `a battery the phone did not report has no level`() {
        assertNull(batteryPercentOf(level = -1, scale = 100))
        assertNull(batteryPercentOf(level = 50, scale = 0))
    }

    @Test
    fun `charging and full are charging, discharging is not`() {
        assertEquals(true, chargingOf(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(true, chargingOf(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(false, chargingOf(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(false, chargingOf(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
    }

    @Test
    fun `a battery status the phone did not report is unknown`() {
        assertNull(chargingOf(BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertNull(chargingOf(NO_STATUS))
    }

    private companion object {
        const val UNNAMED_STATUS = 42
        const val NO_STATUS = -1
    }
}
