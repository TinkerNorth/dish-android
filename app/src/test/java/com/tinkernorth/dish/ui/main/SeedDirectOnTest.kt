// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.usb.PathChoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedDirectOnTest {
    private fun device(
        isUsbSynthetic: Boolean = false,
        transport: Transport = Transport.Usb,
    ) = PhysicalGamepadRegistry.Device(
        id = 7,
        name = "Pad",
        isUsbSynthetic = isUsbSynthetic,
        vendorId = 0x054C,
        productId = 0x09CC,
        transport = transport,
    )

    @Test
    fun `no device seeds the toggle off`() {
        assertFalse(seedDirectOn(null, null))
    }

    @Test
    fun `a device already on Direct seeds the toggle on`() {
        assertTrue(seedDirectOn(device(isUsbSynthetic = true), null))
    }

    @Test
    fun `a USB device whose resolved path is Direct seeds the toggle on`() {
        assertTrue(seedDirectOn(device(), PathChoice.Direct))
    }

    @Test
    fun `a USB device whose resolved path is Standard seeds the toggle off`() {
        assertFalse(seedDirectOn(device(), PathChoice.Standard))
    }

    @Test
    fun `an untracked USB device seeds the toggle off`() {
        assertFalse(seedDirectOn(device(), null))
    }

    @Test
    fun `a Bluetooth device never seeds the toggle on`() {
        assertFalse(seedDirectOn(device(transport = Transport.Bluetooth), PathChoice.Direct))
    }
}
