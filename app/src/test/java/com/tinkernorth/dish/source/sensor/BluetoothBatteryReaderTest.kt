// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [BluetoothBatteryReader.matchBondedDeviceName] — the pure
 * name-match that pairs a physical gamepad's `InputDevice` to a bonded
 * `BluetoothDevice` in the API < 31 battery fallback (M-1).
 *
 * The reflection / Bluetooth-stack parts of [BluetoothBatteryReader] need a
 * device and cannot be unit-tested; the matching heuristic can and is the part
 * with real branching.
 */
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
        // An InputDevice name and its bonded BluetoothDevice name occasionally
        // differ only in letter case across the input vs Bluetooth stacks.
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
        // A USB-wired pad's name won't match any bonded device — the caller
        // then falls through to the phone-battery path.
        assertNull(
            BluetoothBatteryReader.matchBondedDeviceName(
                "USB Gamepad",
                listOf("Headphones", "Keyboard"),
            ),
        )
    }

    @Test
    fun `empty input name never matches`() {
        // A blank InputDevice name must not match a (hypothetical) blank bond.
        assertNull(BluetoothBatteryReader.matchBondedDeviceName("", listOf("", "Pad")))
    }

    @Test
    fun `empty bonded set returns null`() {
        assertNull(BluetoothBatteryReader.matchBondedDeviceName("Pad", emptyList()))
    }

    @Test
    fun `first match wins when names collide`() {
        // Two bonds with the same name is a degenerate case; the helper just
        // returns the first — deterministic, and good enough for a best-effort
        // battery read.
        val match =
            BluetoothBatteryReader.matchBondedDeviceName(
                "Controller",
                listOf("Controller", "Controller"),
            )
        assertEquals("Controller", match)
    }
}
