// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRoutingTest {
    private val phone = BatterySample(level = 55, status = BatteryValidator.STATUS_CHARGING)
    private val unknownPhone = BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_CHARGING)

    private fun device(
        level: Int,
        status: Int = BatteryValidator.STATUS_DISCHARGING,
    ) = BatterySample(level, status)

    @Test
    fun `a pad lower than the phone wires the pad sample`() {
        val pad = device(level = 12)
        assertEquals(pad, route(device = pad, phone = phone).wire)
    }

    @Test
    fun `a pad higher than the phone wires the phone sample`() {
        val pad = device(level = 90)
        assertEquals(phone, route(device = pad, phone = phone).wire)
    }

    @Test
    fun `a pad with no battery of its own wires the phone sample`() {
        assertEquals(phone, route(device = null, phone = phone).wire)
    }

    @Test
    fun `an unknown phone level and a known pad level wire the pad sample`() {
        val pad = device(level = 97)
        assertEquals(pad, route(device = pad, phone = unknownPhone).wire)
    }

    @Test
    fun `a level tie wires the pad sample`() {
        val pad = device(level = 55)
        assertEquals(pad, route(device = pad, phone = phone).wire)
    }

    @Test
    fun `the wire sample carries the status of whichever side won`() {
        val lowPad = device(level = 20, status = BatteryValidator.STATUS_DISCHARGING)
        assertEquals(BatteryValidator.STATUS_DISCHARGING, route(device = lowPad, phone = phone).wire.status)

        val highPad = device(level = 80, status = BatteryValidator.STATUS_DISCHARGING)
        assertEquals(BatteryValidator.STATUS_CHARGING, route(device = highPad, phone = phone).wire.status)
    }

    @Test
    fun `a pad with an unknown level loses the lowest pick to a known phone level`() {
        val pad = device(level = BatteryValidator.LEVEL_UNKNOWN)
        assertEquals(phone, route(device = pad, phone = phone).wire)
    }

    @Test
    fun `both levels unknown wire the pad sample`() {
        val pad = device(level = BatteryValidator.LEVEL_UNKNOWN, status = BatteryValidator.STATUS_UNKNOWN)
        assertEquals(pad, route(device = pad, phone = unknownPhone).wire)
    }

    @Test
    fun `an unreadable phone battery wires the pad sample`() {
        val pad = device(level = 40)
        assertEquals(pad, route(device = pad, phone = null).wire)
    }

    @Test
    fun `an unreadable phone battery and no pad battery wire the unknown sentinel`() {
        assertEquals(UNKNOWN_SAMPLE, route(device = null, phone = null).wire)
    }

    @Test
    fun `a pad displays its own reading whichever side wins the wire`() {
        val lowPad = device(level = 12)
        assertEquals(lowPad, route(device = lowPad, phone = phone).display)
        val highPad = device(level = 90)
        assertEquals(highPad, route(device = highPad, phone = phone).display)
    }

    @Test
    fun `a pad with no battery displays nothing`() {
        assertNull(route(device = null, phone = phone).display)
    }

    @Test
    fun `lowest with no phone sample answers the device`() {
        val pad = device(level = 40)
        assertEquals(pad, lowest(pad, phone = null))
    }

    @Test
    fun `lowest answers whichever level is lower`() {
        assertEquals(device(level = 10), lowest(device(level = 10), phone))
        assertEquals(phone, lowest(device(level = 90), phone))
    }

    @Test
    fun `lowest keeps the device on a tie, so a matching phone never displaces it`() {
        val pad = device(level = 55)
        assertEquals(pad, lowest(pad, phone))
    }

    @Test
    fun `an unknown level never wins the comparison`() {
        val unknownPad = device(level = BatteryValidator.LEVEL_UNKNOWN)
        assertEquals(phone, lowest(unknownPad, phone))
        val unknownStatusPhone = BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_UNKNOWN)
        assertEquals(device(level = 5), lowest(device(level = 5), unknownStatusPhone))
    }

    @Test
    fun `two unknown levels keep the device`() {
        val unknownPad = device(level = BatteryValidator.LEVEL_UNKNOWN)
        val unknownStatusPhone = BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_UNKNOWN)
        assertEquals(unknownPad, lowest(unknownPad, unknownStatusPhone))
    }
}
