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
        assertEquals(QUIRK_N64_HID_LAYOUT, resolveGamepadQuirk(0x057E, 0x2019, reportsRxAxis = true))
    }

    @Test
    fun `n64 online pad without an rx axis is the kernel driver layout`() {
        assertEquals(QUIRK_N64_DRIVER_LAYOUT, resolveGamepadQuirk(0x057E, 0x2019, reportsRxAxis = false))
    }

    @Test
    fun `n64 layouts carry no face swaps`() {
        for (rx in listOf(true, false)) {
            assertEquals(0, resolveGamepadQuirk(0x057E, 0x2019, rx) and (QUIRK_SWAP_AB or QUIRK_SWAP_XY))
        }
    }

    @Test
    fun `the rx axis changes nothing for other nintendo pads`() {
        assertEquals(quirk(0x057E, 0x2009), resolveGamepadQuirk(0x057E, 0x2009, reportsRxAxis = true))
    }

    @Test
    fun `n64 layout quirk bits match the native contract`() {
        assertEquals(0x08, QUIRK_N64_HID_LAYOUT)
        assertEquals(0x10, QUIRK_N64_DRIVER_LAYOUT)
        val older = QUIRK_SWAP_AB or QUIRK_SWAP_XY or QUIRK_SWITCH_LAYOUT
        assertEquals(0, (QUIRK_N64_HID_LAYOUT or QUIRK_N64_DRIVER_LAYOUT) and older)
    }

    private fun quirk(
        vendorId: Int,
        productId: Int,
    ): Int = resolveGamepadQuirk(vendorId, productId, reportsRxAxis = false)
}
