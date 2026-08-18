// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredUsbPresenceTest {
    private fun device(
        id: Int = 5,
        vendorId: Int = 0x1949,
        productId: Int = 0x0419,
        transport: Transport = Transport.Bluetooth,
        isUsbSynthetic: Boolean = false,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Amazon Game Controller",
        vendorId = vendorId,
        productId = productId,
        transport = transport,
        isUsbSynthetic = isUsbSynthetic,
    )

    private fun tracked(
        vendorId: Int = 0x1949,
        productId: Int = 0x041A,
        phase: UsbPhase = UsbPhase.Routed,
        usbPresent: Boolean = true,
        frameworkId: Int? = null,
        syntheticId: Int? = null,
    ) = UsbController(
        vendorId = vendorId,
        productId = productId,
        name = "Amazon Luna Controller",
        phase = phase,
        usbPresent = usbPresent,
        frameworkId = frameworkId,
        syntheticId = syntheticId,
    )

    @Test
    fun `a luna-shaped orphan lights the wired switch on the same vendor's bluetooth card`() {
        assertTrue(
            wiredUsbPresentFor(device(vendorId = 0x1949, productId = 0x0419), listOf(tracked(vendorId = 0x1949, productId = 0x041A))),
        )
    }

    @Test
    fun `a same-id twin whose framework points at this card lights the switch`() {
        val ds4 = device(id = 5, vendorId = 0x054C, productId = 0x05C4)
        val twin = tracked(vendorId = 0x054C, productId = 0x05C4, frameworkId = 5)
        assertTrue(wiredUsbPresentFor(ds4, listOf(twin)))
    }

    @Test
    fun `a twin claimed by a different card does not light this one`() {
        val ds4 = device(id = 5, vendorId = 0x054C, productId = 0x05C4)
        val twin = tracked(vendorId = 0x054C, productId = 0x05C4, frameworkId = 9)
        assertFalse(wiredUsbPresentFor(ds4, listOf(twin)))
    }

    @Test
    fun `a different vendor's orphan never lights the switch`() {
        assertFalse(wiredUsbPresentFor(device(vendorId = 0x054C), listOf(tracked(vendorId = 0x1949))))
    }

    @Test
    fun `a usb card never lights the switch`() {
        assertFalse(wiredUsbPresentFor(device(transport = Transport.Usb), listOf(tracked())))
    }

    @Test
    fun `a synthetic card never lights the switch`() {
        assertFalse(wiredUsbPresentFor(device(transport = Transport.Usb, isUsbSynthetic = true), listOf(tracked())))
    }

    @Test
    fun `no tracked usb controllers means no switch`() {
        assertFalse(wiredUsbPresentFor(device(), emptyList()))
    }

    @Test
    fun `a claim in flight does not light the switch`() {
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(phase = UsbPhase.Claiming))))
    }

    @Test
    fun `a pad already claimed direct does not light the switch`() {
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(phase = UsbPhase.Direct, syntheticId = 77))))
    }

    @Test
    fun `transient path states do not light the switch`() {
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(phase = UsbPhase.AwaitingFramework))))
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(phase = UsbPhase.RestoreStuck))))
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(phase = UsbPhase.NeedsReplug))))
    }

    @Test
    fun `an entry whose cable left does not light the switch`() {
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(usbPresent = false))))
    }

    @Test
    fun `a routed entry still holding a synthetic does not light the switch`() {
        assertFalse(wiredUsbPresentFor(device(), listOf(tracked(syntheticId = 77))))
    }

    @Test
    fun `one qualifying orphan among disqualified entries is enough`() {
        val controllers =
            listOf(
                tracked(vendorId = 0x054C, productId = 0x05C4),
                tracked(phase = UsbPhase.Claiming),
                tracked(usbPresent = false),
                tracked(),
            )
        assertTrue(wiredUsbPresentFor(device(), controllers))
    }
}
