// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadQuirksTest {
    @Test
    fun `nintendo controllers swap both face-button pairs`() {
        val q = quirk(0x057E, 0x2009)
        assertTrue(q and QUIRK_SWAP_AB != 0)
        assertTrue(q and QUIRK_SWAP_XY != 0)
    }

    @Test
    fun `nintendo swap applies regardless of product id`() {
        assertEquals(quirk(0x057E, 0x2009), quirk(0x057E, 0x0000))
        assertEquals(quirk(0x057E, 0x2009), quirk(0x057E, 0x0180))
    }

    @Test
    fun `xbox controllers get no quirk`() {
        assertEquals(QUIRK_NONE, quirk(0x045E, 0x028E))
    }

    @Test
    fun `sony controllers get no quirk`() {
        assertEquals(QUIRK_NONE, quirk(0x054C, 0x09CC))
    }

    @Test
    fun `unknown vendor gets no quirk`() {
        assertEquals(QUIRK_NONE, quirk(0x0000, 0x0000))
    }

    @Test
    fun `pdp wired switch pads get the switch layout quirk`() {
        for (pid in listOf(0x0180, 0x0181, 0x0184, 0x0185, 0x0187)) {
            assertEquals(QUIRK_SWITCH_LAYOUT, quirk(0x0E6F, pid))
        }
    }

    @Test
    fun `pdp afterglow wireless is not switch layout`() {
        assertEquals(QUIRK_NONE, quirk(0x0E6F, 0x0186))
    }

    @Test
    fun `pdp xbox pads get no quirk`() {
        assertEquals(QUIRK_NONE, quirk(0x0E6F, 0x0501))
        assertEquals(QUIRK_NONE, quirk(0x0E6F, 0x013B))
        assertEquals(QUIRK_NONE, quirk(0x0E6F, 0x0201))
    }

    @Test
    fun `switch layout quirk bit matches the native contract`() {
        assertEquals(0x04, QUIRK_SWITCH_LAYOUT)
    }

    @Test
    fun `switch layout does not overlap the swap bits`() {
        assertEquals(0, QUIRK_SWITCH_LAYOUT and (QUIRK_SWAP_AB or QUIRK_SWAP_XY))
    }

    @Test
    fun `n64 online pad with an rx axis is the generic hid layout`() {
        assertEquals(QUIRK_N64_HID_LAYOUT, resolveGamepadQuirk(0x057E, 0x2019, ReportedAxes(rx = true)))
    }

    @Test
    fun `n64 online pad without an rx axis is the kernel driver layout`() {
        assertEquals(QUIRK_N64_DRIVER_LAYOUT, resolveGamepadQuirk(0x057E, 0x2019, ReportedAxes()))
    }

    @Test
    fun `n64 layouts carry no face swaps`() {
        for (axes in listOf(ReportedAxes(rx = true), ReportedAxes())) {
            assertEquals(0, resolveGamepadQuirk(0x057E, 0x2019, axes) and (QUIRK_SWAP_AB or QUIRK_SWAP_XY))
        }
    }

    @Test
    fun `the rx axis changes nothing for other nintendo pads`() {
        assertEquals(quirk(0x057E, 0x2009), resolveGamepadQuirk(0x057E, 0x2009, ReportedAxes(rx = true)))
    }

    @Test
    fun `n64 layout quirk bits match the native contract`() {
        assertEquals(0x08, QUIRK_N64_HID_LAYOUT)
        assertEquals(0x10, QUIRK_N64_DRIVER_LAYOUT)
        val older = QUIRK_SWAP_AB or QUIRK_SWAP_XY or QUIRK_SWITCH_LAYOUT
        assertEquals(0, (QUIRK_N64_HID_LAYOUT or QUIRK_N64_DRIVER_LAYOUT) and older)
    }

    @Test
    fun `8bitdo d-input identities get the 8bitdo quirk`() {
        for (pid in listOf(0x5006, 0x0651, 0x3100, 0x3105)) {
            assertEquals(QUIRK_EIGHTBITDO_DINPUT, quirk(0x2DC8, pid))
        }
    }

    @Test
    fun `8bitdo xinput identities get no quirk`() {
        assertEquals(QUIRK_NONE, quirk(0x2DC8, 0x6001))
        assertEquals(QUIRK_NONE, quirk(0x2DC8, 0x310A))
    }

    @Test
    fun `a microsoft pad with triggers on z and rz and no trigger axis is the xpad layout`() {
        assertEquals(QUIRK_XPAD_TRIGGER_AXES, resolveGamepadQuirk(0x045E, 0x0B00, XPAD_AXES))
        assertEquals(QUIRK_XPAD_TRIGGER_AXES, resolveGamepadQuirk(0x045E, 0x02EA, XPAD_AXES))
    }

    @Test
    fun `a microsoft pad whose layout names its trigger axes keeps the standard layout`() {
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x045E, 0x028E, XPAD_AXES.copy(lTrigger = true, rTrigger = true)))
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x045E, 0x0B00, XPAD_AXES.copy(brake = true, gas = true)))
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x045E, 0x0B00, XPAD_AXES.copy(rx = false)))
    }

    @Test
    fun `the xpad axis shape alone names no other vendor's pad`() {
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x0E6F, 0x0501, XPAD_AXES))
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x0000, 0x0000, XPAD_AXES))
        assertEquals(QUIRK_EIGHTBITDO_DINPUT, resolveGamepadQuirk(0x2DC8, 0x5006, XPAD_AXES))
    }

    @Test
    fun `the new quirk bits match the native contract and sit above the older ones`() {
        assertEquals(0x20, QUIRK_EIGHTBITDO_DINPUT)
        assertEquals(0x40, QUIRK_XPAD_TRIGGER_AXES)
        val older =
            QUIRK_SWAP_AB or QUIRK_SWAP_XY or QUIRK_SWITCH_LAYOUT or QUIRK_N64_HID_LAYOUT or QUIRK_N64_DRIVER_LAYOUT
        assertEquals(0, (QUIRK_EIGHTBITDO_DINPUT or QUIRK_XPAD_TRIGGER_AXES) and older)
    }

    private fun quirk(
        vendorId: Int,
        productId: Int,
    ): Int = resolveGamepadQuirk(vendorId, productId, ReportedAxes())

    private companion object {
        val XPAD_AXES = ReportedAxes(z = true, rz = true, rx = true, ry = true)
    }
}
