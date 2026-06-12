// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRoutingTest {
    private val phone = BatterySample(level = 55, status = BatteryValidator.STATUS_CHARGING)

    private fun device(
        level: Int,
        status: Int = BatteryValidator.STATUS_DISCHARGING,
    ) = BatterySample(level, status)

    @Test
    fun `usb pad without its own battery displays nothing and wires the phone`() {
        val routed = BatteryRouting.route(Transport.Usb, device = null, phone = phone)
        assertNull(routed.display)
        assertEquals(phone, routed.wire)
    }

    @Test
    fun `bluetooth pad without its own battery displays nothing and wires the phone`() {
        val routed = BatteryRouting.route(Transport.Bluetooth, device = null, phone = phone)
        assertNull(routed.display)
        assertEquals(phone, routed.wire)
    }

    @Test
    fun `usb pad with a battery displays the device but wires the phone`() {
        val pad = device(level = 12)
        val routed = BatteryRouting.route(Transport.Usb, device = pad, phone = phone)
        assertEquals(pad, routed.display)
        assertEquals(phone, routed.wire)
    }

    @Test
    fun `bluetooth pad lower than the phone displays and wires the device`() {
        val pad = device(level = 20)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = phone)
        assertEquals(pad, routed.display)
        assertEquals(pad, routed.wire)
    }

    @Test
    fun `bluetooth pad higher than the phone displays the device but wires the phone`() {
        val pad = device(level = 90)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = phone)
        assertEquals(pad, routed.display)
        assertEquals(phone, routed.wire)
    }

    @Test
    fun `bluetooth level tie wires the device sample`() {
        val pad = device(level = 55)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = phone)
        assertEquals(pad, routed.wire)
    }

    @Test
    fun `bluetooth pad with unknown level loses the lowest pick to a known phone level`() {
        val pad = device(level = BatteryValidator.LEVEL_UNKNOWN)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = phone)
        assertEquals(phone, routed.wire)
    }

    @Test
    fun `phone with unknown level loses the lowest pick to a known device level`() {
        val pad = device(level = 97)
        val unknownPhone =
            BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_CHARGING)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = unknownPhone)
        assertEquals(pad, routed.wire)
    }

    @Test
    fun `both levels unknown wires the device sample`() {
        val pad = device(level = BatteryValidator.LEVEL_UNKNOWN, status = BatteryValidator.STATUS_UNKNOWN)
        val unknownPhone =
            BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_CHARGING)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = unknownPhone)
        assertEquals(pad, routed.wire)
    }

    @Test
    fun `unreadable phone battery on usb wires the unknown sentinel`() {
        val pad = device(level = 40)
        val routed = BatteryRouting.route(Transport.Usb, device = pad, phone = null)
        assertEquals(pad, routed.display)
        assertEquals(BatteryRouting.UNKNOWN_SAMPLE, routed.wire)
    }

    @Test
    fun `unreadable phone battery without a device battery wires the unknown sentinel`() {
        val routed = BatteryRouting.route(Transport.Usb, device = null, phone = null)
        assertNull(routed.display)
        assertEquals(BatteryRouting.UNKNOWN_SAMPLE, routed.wire)
    }

    @Test
    fun `unreadable phone battery on bluetooth wires the device sample`() {
        val pad = device(level = 40)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = null)
        assertEquals(pad, routed.wire)
    }

    @Test
    fun `wire sample carries the status of whichever side won the lowest pick`() {
        val pad = device(level = 20, status = BatteryValidator.STATUS_DISCHARGING)
        val routed = BatteryRouting.route(Transport.Bluetooth, device = pad, phone = phone)
        assertEquals(BatteryValidator.STATUS_DISCHARGING, routed.wire.status)

        val flipped = BatteryRouting.route(Transport.Bluetooth, device = device(level = 80), phone = phone)
        assertEquals(BatteryValidator.STATUS_CHARGING, flipped.wire.status)
    }
}
