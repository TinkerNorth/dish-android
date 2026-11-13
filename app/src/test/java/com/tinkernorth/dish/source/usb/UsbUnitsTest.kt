// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbUnitsTest {
    private fun controller(name: String) = UsbController(vendorId = 0, productId = 0, name = name, phase = UsbPhase.Routed)

    @Test
    fun `a model's first device is unit 0`() {
        assertEquals(UsbUnitKey(MODEL, 0), freeUnitKey(MODEL, emptyList()))
    }

    @Test
    fun `a model's next device takes the next unit`() {
        assertEquals(UsbUnitKey(MODEL, 1), freeUnitKey(MODEL, listOf(UsbUnitKey(MODEL, 0))))
    }

    @Test
    fun `a device plugged in after another left takes the unit it left`() {
        assertEquals(UsbUnitKey(MODEL, 0), freeUnitKey(MODEL, listOf(UsbUnitKey(MODEL, 1))))
    }

    @Test
    fun `another model's devices hold none of the model's units`() {
        assertEquals(UsbUnitKey(MODEL, 0), freeUnitKey(MODEL, listOf(UsbUnitKey(OTHER_MODEL, 0))))
    }

    @Test
    fun `each model shows the device with its lowest unit`() {
        val byUnit =
            mapOf(
                UsbUnitKey(MODEL, 2) to controller("third"),
                UsbUnitKey(MODEL, 1) to controller("second"),
                UsbUnitKey(OTHER_MODEL, 0) to controller("other"),
            )

        assertEquals(mapOf(MODEL to controller("second"), OTHER_MODEL to controller("other")), firstOfEachModel(byUnit))
    }

    private companion object {
        const val MODEL = 0x054C0CE6
        const val OTHER_MODEL = 0x045E028E
    }
}
