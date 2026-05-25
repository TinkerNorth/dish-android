// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothBatteryReaderTest {
    @Test
    fun `exact name match is found`() {
        val match =
            BluetoothBatteryReader.matchBondedDeviceName(
                "DualSense Wireless Controller",
                listOf("Pixel Buds", "DualSense Wireless Controller", "Car Audio"),
            )
        assertEquals("DualSense Wireless Controller", match)
    }

    @Test
    fun `match is case-insensitive`() {
        val match =
            BluetoothBatteryReader.matchBondedDeviceName(
                "8bitdo pro 2",
                listOf("8BitDo Pro 2"),
            )
        assertEquals("8BitDo Pro 2", match)
    }

    @Test
    fun `surrounding whitespace is tolerated on both sides`() {
        val match =
            BluetoothBatteryReader.matchBondedDeviceName(
                "  Xbox Wireless Controller  ",
                listOf("Xbox Wireless Controller "),
            )
        assertEquals("Xbox Wireless Controller ", match)
    }

    @Test
    fun `no match returns null`() {
        assertNull(
            BluetoothBatteryReader.matchBondedDeviceName(
                "USB Gamepad",
                listOf("Headphones", "Keyboard"),
            ),
        )
    }

    @Test
    fun `empty input name never matches`() {
        assertNull(BluetoothBatteryReader.matchBondedDeviceName("", listOf("", "Pad")))
    }

    @Test
    fun `empty bonded set returns null`() {
        assertNull(BluetoothBatteryReader.matchBondedDeviceName("Pad", emptyList()))
    }

    @Test
    fun `first match wins when names collide`() {
        val match =
            BluetoothBatteryReader.matchBondedDeviceName(
                "Controller",
                listOf("Controller", "Controller"),
            )
        assertEquals("Controller", match)
    }
}
