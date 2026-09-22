// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the HID report descriptor byte for byte: a host that has bonded to the emulated pad caches
// this descriptor, and a byte moving here means every bonded host sees a different device.
class BluetoothGamepadDescriptorTest {
    private val expected =
        (
            "05 01 09 05 A1 01 85 01 05 09 19 01 29 0E 15 00 25 01 75 01 95 0E 81 02 75 01 95 02 81 03 " +
                "05 01 09 39 15 01 25 08 35 00 46 3B 01 65 14 75 04 95 01 81 42 75 04 95 01 81 03 35 00 45 00 65 00 " +
                "05 01 09 30 09 31 16 00 80 26 FF 7F 75 10 95 02 81 02 09 33 09 34 16 00 80 26 FF 7F 75 10 95 02 81 02 " +
                "05 02 09 C5 09 C4 15 00 26 FF 00 75 08 95 02 81 02 C0"
        ).split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `the descriptor is the 115 bytes bonded hosts cached`() {
        val descriptor = buildHidDescriptor()
        assertEquals(115, descriptor.size)
        assertArrayEquals(expected, descriptor)
    }

    @Test
    fun `the descriptor opens an application collection it closes`() {
        val descriptor = buildHidDescriptor()
        assertEquals(0xA1.toByte(), descriptor[4])
        assertEquals(0xC0.toByte(), descriptor.last())
    }
}
