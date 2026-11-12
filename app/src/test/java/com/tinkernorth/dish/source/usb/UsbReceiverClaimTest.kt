// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbReceiverClaimTest {
    private val valve = 0x28DE
    private val steamControllerDongle = 0x1142
    private val steamControllerCable = 0x1102
    private val proteusDongle = 0x1304
    private val nereidDongle = 0x1305
    private val tritonCable = 0x1302

    private val plainHid = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, 0x00, 0x00)
    private val bootKeyboard = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, 0x01, 0x01)
    private val bootMouse = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, 0x01, 0x02)
    private val xinput = UsbInterfaceFacts(UsbConstants.USB_CLASS_VENDOR_SPEC, 0x5D, 0x01)
    private val audio = UsbInterfaceFacts(UsbConstants.USB_CLASS_AUDIO, 0x00, 0x00)

    private fun numbered(
        vararg numbers: Int,
        facts: UsbInterfaceFacts = plainHid,
    ) = numbers.map { UsbInterfaceCandidate(it, facts) }

    // hid-steam's steam_is_valve_interface: the 2015 receiver is its keyboard, then four slots.
    private val steamReceiverShaped = numbered(0, facts = bootKeyboard) + numbered(1, 2, 3, 4)

    // hid-steam: the 2026 receivers' slots are 2 to 5, with two interfaces before them and the pogo
    // pins after.
    private val tritonReceiverShaped = numbered(0, 1) + numbered(2, 3, 4, 5) + numbered(6)

    private fun openedNumbers(
        vendorId: Int,
        productId: Int,
        candidates: List<UsbInterfaceCandidate>,
    ): List<Int> = interfacesToClaim(vendorId, productId, candidates).map { candidates[it].number }

    @Test
    fun `the 2015 receiver opens each of its four slots`() {
        assertEquals(listOf(1, 2, 3, 4), openedNumbers(valve, steamControllerDongle, steamReceiverShaped))
    }

    @Test
    fun `the Proteus receiver opens each of its four slots`() {
        assertEquals(listOf(2, 3, 4, 5), openedNumbers(valve, proteusDongle, tritonReceiverShaped))
    }

    @Test
    fun `the Nereid receiver opens each of its four slots`() {
        assertEquals(listOf(2, 3, 4, 5), openedNumbers(valve, nereidDongle, tritonReceiverShaped))
    }

    @Test
    fun `a receiver opens the slots it lists, in interface order`() {
        val listed = numbered(4, 0, 2)

        assertEquals(listOf(2, 4), openedNumbers(valve, proteusDongle, listed))
    }

    @Test
    fun `a receiver opens a slot listed twice once`() {
        val alternateSetting = numbered(1, 1, 2)

        assertEquals(listOf(1, 2), openedNumbers(valve, steamControllerDongle, alternateSetting))
    }

    @Test
    fun `a receiver whose slots carry boot interfaces still opens them`() {
        val bootSlots = numbered(0, facts = bootKeyboard) + numbered(1, 2, facts = bootMouse)

        assertEquals(listOf(1, 2), openedNumbers(valve, steamControllerDongle, bootSlots))
    }

    @Test
    fun `a receiver with no slot listed opens nothing`() {
        assertEquals(emptyList<Int>(), openedNumbers(valve, proteusDongle, numbered(0, 1, 6)))
    }

    @Test
    fun `the wired Steam Controller opens the one interface it picks`() {
        val wired = numbered(0, facts = bootMouse) + numbered(1, facts = bootKeyboard) + numbered(2)

        assertEquals(listOf(2), openedNumbers(valve, steamControllerCable, wired))
    }

    @Test
    fun `the 2026 controller on its cable opens the one interface it picks`() {
        assertEquals(listOf(0), openedNumbers(valve, tritonCable, tritonReceiverShaped))
    }

    @Test
    fun `any other model opens the one interface it picks`() {
        val pad = numbered(0, facts = audio) + numbered(1, facts = xinput) + numbered(2)

        assertEquals(listOf(1), openedNumbers(0x045E, 0x028E, pad))
    }

    @Test
    fun `another vendor's product id of a receiver opens the one interface it picks`() {
        assertEquals(listOf(1), openedNumbers(0x045E, steamControllerDongle, steamReceiverShaped))
    }

    @Test
    fun `a model with nothing claimable opens nothing`() {
        assertEquals(emptyList<Int>(), openedNumbers(0x045E, 0x028E, numbered(0, facts = audio)))
    }
}
