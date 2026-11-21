// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ServedRowsTest {
    private fun indexOf(vararg records: ControllerRecord) =
        ControllerDbIndex(db(2, *records), ControllerStrings(emptyMap(), null, emptySet()))

    @Test
    fun `each usb identity becomes one row of parallel arrays`() {
        val rows =
            servedRowsOf(
                indexOf(
                    record("a", usb = listOf("045e:028e", "045e:028f"), family = "xinput360"),
                    record("b", usb = listOf("054c:0ce6"), family = "dualsense", state = VERIFICATION_IMPORTED),
                ),
            )
        assertArrayEquals(intArrayOf(0x045E, 0x045E, 0x054C), rows.vendorIds)
        assertArrayEquals(intArrayOf(0x028E, 0x028F, 0x0CE6), rows.productIds)
        assertArrayEquals(intArrayOf(1, 1, 4), rows.parsers)
        assertEquals(listOf(true, true, false), rows.verified.toList())
    }

    @Test
    fun `families, init kinds and button orders map to the native enums`() {
        val rows =
            servedRowsOf(
                indexOf(
                    record("gip", usb = listOf("0001:0001"), family = "xboxOneGip", init = "xboxOneS"),
                    record("hid", usb = listOf("0001:0002"), family = FAMILY_HID, mapping = "playstationClassic"),
                    record("tri", usb = listOf("0001:0003"), family = "steamTriton", init = "steamTritonQuiet"),
                    record("sw", usb = listOf("0001:0004"), family = "switchProUsb", init = "switchProHandshake"),
                ),
            )
        assertArrayEquals(intArrayOf(2, 7, 11, 5), rows.parsers)
        assertArrayEquals(intArrayOf(3, 0, 5, 2), rows.inits)
        assertArrayEquals(intArrayOf(0, 3, 0, 0), rows.orders)
    }

    @Test
    fun `every family of the vocabulary has its native parser value`() {
        assertEquals(
            listOf(1, 8, 2, 3, 4, 5, 10, 6, 7, 9, 11, 12),
            listOf(
                "xinput360",
                "xinput360Wireless",
                "xboxOneGip",
                "dualshock4",
                "dualsense",
                "switchProUsb",
                "switchN64Usb",
                "stadia",
                "hid",
                "steamController",
                "steamTriton",
                "steamControllerBle",
            ).map { NATIVE_PARSERS.getValue(it) },
        )
    }

    @Test
    fun `an inert record is not handed down`() {
        val rows = servedRowsOf(indexOf(record("future", usb = listOf("0001:0001")).requiring(listOf("gameCubeUsb"))))
        assertEquals(0, rows.size)
    }

    @Test
    fun `a bluetooth record is not handed down to the usb attach path`() {
        val rows = servedRowsOf(indexOf(record("blue", bluetooth = listOf("28de:1106"), transport = TRANSPORT_BLUETOOTH)))
        assertEquals(0, rows.size)
    }

    @Test
    fun `an unknown init kind or button order makes the record inert`() {
        val rows =
            servedRowsOf(
                indexOf(
                    record("a", usb = listOf("0001:0001"), init = "futureInit"),
                    record("b", usb = listOf("0001:0002"), family = FAMILY_HID, mapping = "futureOrder"),
                ),
            )
        assertEquals(0, rows.size)
    }

    @Test
    fun `a malformed identity is skipped and the rest are kept`() {
        val rows = servedRowsOf(indexOf(record("a", usb = listOf("nope", "0001:0002"))))
        assertArrayEquals(intArrayOf(2), rows.productIds)
    }
}
