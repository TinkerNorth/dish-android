// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryValidatorTest {
    private val emitted = mutableListOf<BatterySample>()
    private val emit = BatteryValidator.Emit { s -> emitted += s }

    @Test
    fun `first sample is emitted`() {
        assertTrue(publishBatterySample(BatterySample(75, BatteryValidator.STATUS_DISCHARGING), emit))
        assertEquals(1, emitted.size)
        assertEquals(75, emitted[0].level)
    }

    @Test
    fun `every sample is forwarded, including unchanged ones`() {
        val s = BatterySample(100, BatteryValidator.STATUS_WIRED)
        assertTrue(publishBatterySample(s, emit))
        assertTrue(publishBatterySample(s, emit))
        assertTrue(publishBatterySample(s, emit))
        assertEquals(3, emitted.size)
    }

    @Test
    fun `level change is forwarded`() {
        publishBatterySample(BatterySample(75, BatteryValidator.STATUS_DISCHARGING), emit)
        assertTrue(publishBatterySample(BatterySample(74, BatteryValidator.STATUS_DISCHARGING), emit))
        assertTrue(publishBatterySample(BatterySample(73, BatteryValidator.STATUS_DISCHARGING), emit))
        assertEquals(3, emitted.size)
    }

    @Test
    fun `status change at the same level is forwarded`() {
        publishBatterySample(BatterySample(80, BatteryValidator.STATUS_DISCHARGING), emit)
        assertTrue(publishBatterySample(BatterySample(80, BatteryValidator.STATUS_CHARGING), emit))
        assertEquals(2, emitted.size)
        assertEquals(BatteryValidator.STATUS_CHARGING, emitted[1].status)
    }

    @Test
    fun `0xFF level is accepted as the unknown sentinel`() {
        assertTrue(
            publishBatterySample(
                BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_CHARGING),
                emit,
            ),
        )
        assertEquals(1, emitted.size)
    }

    @Test
    fun `bogus level above 100 is rejected`() {
        assertFalse(publishBatterySample(BatterySample(101, BatteryValidator.STATUS_DISCHARGING), emit))
        assertFalse(publishBatterySample(BatterySample(254, BatteryValidator.STATUS_DISCHARGING), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `negative level is rejected`() {
        assertFalse(publishBatterySample(BatterySample(-1, BatteryValidator.STATUS_DISCHARGING), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `status outside the documented set is rejected`() {
        assertFalse(publishBatterySample(BatterySample(50, BatteryValidator.STATUS_MAX + 1), emit))
        assertFalse(publishBatterySample(BatterySample(50, -1), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `wire constants match the protocol spec`() {
        // Contract with satellite/src/core/types.h: drift breaks every paired receiver.
        assertEquals(0xFF, BatteryValidator.LEVEL_UNKNOWN)
        assertEquals(0, BatteryValidator.STATUS_UNKNOWN)
        assertEquals(1, BatteryValidator.STATUS_DISCHARGING)
        assertEquals(2, BatteryValidator.STATUS_CHARGING)
        assertEquals(3, BatteryValidator.STATUS_FULL)
        assertEquals(4, BatteryValidator.STATUS_WIRED)
        assertEquals(30, BatteryValidator.REPORT_INTERVAL_SECONDS)
        assertEquals(30_000L, BatteryValidator.REPORT_INTERVAL_MS)
    }
}
