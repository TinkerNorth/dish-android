// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbInterfacePickTest {
    private val valve = 0x28DE
    private val proteusDongle = 0x1304
    private val nereidDongle = 0x1305
    private val tritonCable = 0x1302
    private val steamControllerDongle = 0x1142

    private val plainHid = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, 0x00, 0x00)
    private val bootKeyboard = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, 0x01, 0x01)
    private val bootMouse = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, 0x01, 0x02)
    private val audio = UsbInterfaceFacts(UsbConstants.USB_CLASS_AUDIO, 0x00, 0x00)

    private fun numbered(
        vararg numbers: Int,
        facts: UsbInterfaceFacts = plainHid,
    ) = numbers.map { UsbInterfaceCandidate(it, facts) }

    private val dongleShaped = numbered(0, 1) + numbered(2, 3, 4, 5)

    private fun pickedNumber(
        productId: Int,
        candidates: List<UsbInterfaceCandidate>,
    ): Int? = pickGameInterface(valve, productId, candidates)?.let { candidates[it].number }

    @Test
    fun `the Proteus dongle never picks a non-boot hid interface numbered below 2`() {
        assertEquals(2, pickedNumber(proteusDongle, dongleShaped))
    }

    @Test
    fun `the Nereid dongle never picks a non-boot hid interface numbered below 2`() {
        assertEquals(2, pickedNumber(nereidDongle, dongleShaped))
    }

    @Test
    fun `a dongle never picks an interface numbered above 5`() {
        assertNull(pickedNumber(proteusDongle, numbered(0, 1, 6, 7)))
    }

    @Test
    fun `a dongle still prefers the best rank inside 2 to 5`() {
        val candidates = listOf(UsbInterfaceCandidate(2, bootKeyboard), UsbInterfaceCandidate(3, plainHid))
        assertEquals(3, pickedNumber(proteusDongle, candidates))
    }

    @Test
    fun `a dongle with only boot interfaces in 2 to 5 still picks the first of them`() {
        val candidates = listOf(UsbInterfaceCandidate(1, plainHid), UsbInterfaceCandidate(4, bootMouse))
        assertEquals(4, pickedNumber(proteusDongle, candidates))
    }

    @Test
    fun `the 2026 controller on its cable keeps the first best-ranked interface`() {
        assertEquals(0, pickedNumber(tritonCable, dongleShaped))
    }

    @Test
    fun `the first generation Steam dongle keeps the first best-ranked interface`() {
        assertEquals(0, pickedNumber(steamControllerDongle, dongleShaped))
    }

    @Test
    fun `another vendor's product id of a dongle keeps the first best-ranked interface`() {
        assertEquals(0, pickGameInterface(0x045E, proteusDongle, dongleShaped))
    }

    @Test
    fun `ties keep the earliest interface`() {
        assertEquals(0, pickGameInterface(0x045E, 0x028E, numbered(7, 8)))
    }

    @Test
    fun `an interface that is never claimed is never picked`() {
        assertNull(pickGameInterface(0x045E, 0x028E, listOf(UsbInterfaceCandidate(0, audio))))
    }

    @Test
    fun `no interfaces pick nothing`() {
        assertNull(pickGameInterface(valve, proteusDongle, emptyList()))
    }
}
