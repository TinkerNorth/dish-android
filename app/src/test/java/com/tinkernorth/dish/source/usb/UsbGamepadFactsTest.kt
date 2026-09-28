// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.app.PendingIntent
import android.hardware.usb.UsbConstants
import android.os.Build
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbGamepadFactsTest {
    private fun vendor(
        subclass: Int,
        protocol: Int,
    ) = UsbInterfaceFacts(UsbConstants.USB_CLASS_VENDOR_SPEC, subclass, protocol)

    private fun hid(
        subclass: Int,
        protocol: Int,
    ) = UsbInterfaceFacts(UsbConstants.USB_CLASS_HID, subclass, protocol)

    @Test
    fun `the xinput data interface outranks everything`() {
        assertEquals(RANK_XINPUT, gameInterfaceRank(vendor(subclass = 0x5D, protocol = 0x01)))
        assertTrue(RANK_XINPUT > RANK_GIP)
    }

    @Test
    fun `a gip interface outranks vendor fallback`() {
        assertEquals(RANK_GIP, gameInterfaceRank(vendor(subclass = 0x47, protocol = 0xD0)))
        assertTrue(RANK_GIP > RANK_VENDOR_FALLBACK)
    }

    @Test
    fun `the xinput audio and aux interfaces are never claimed`() {
        assertEquals(RANK_NONE, gameInterfaceRank(vendor(subclass = 0x5D, protocol = 0x02)))
        assertEquals(RANK_NONE, gameInterfaceRank(vendor(subclass = 0x5D, protocol = 0x03)))
    }

    @Test
    fun `the xinput security interface is never claimed`() {
        assertEquals(RANK_NONE, gameInterfaceRank(vendor(subclass = 0xFD, protocol = 0x13)))
    }

    @Test
    fun `an unknown vendor interface is the fallback`() {
        assertEquals(RANK_VENDOR_FALLBACK, gameInterfaceRank(vendor(subclass = 0x00, protocol = 0x00)))
    }

    @Test
    fun `a plain hid interface outranks vendor fallback`() {
        assertEquals(RANK_HID, gameInterfaceRank(hid(subclass = 0x00, protocol = 0x00)))
        assertTrue(RANK_HID > RANK_VENDOR_FALLBACK)
    }

    @Test
    fun `a boot keyboard or mouse is ranked last but stays claimable`() {
        assertEquals(RANK_HID_BOOT, gameInterfaceRank(hid(subclass = 0x01, protocol = 0x01)))
        assertEquals(RANK_HID_BOOT, gameInterfaceRank(hid(subclass = 0x01, protocol = 0x02)))
        assertTrue(RANK_HID_BOOT > RANK_NONE)
    }

    @Test
    fun `a boot subclass with a pad protocol is an ordinary hid interface`() {
        assertEquals(RANK_HID, gameInterfaceRank(hid(subclass = 0x01, protocol = 0x00)))
    }

    @Test
    fun `an interface of another class is never claimed`() {
        assertEquals(RANK_NONE, gameInterfaceRank(UsbInterfaceFacts(UsbConstants.USB_CLASS_AUDIO, 0x01, 0x00)))
    }

    @Test
    fun `a known model is named by the table`() {
        assertEquals("Xbox 360 Controller", friendlyUsbName("Xbox 360 Controller", ::deviceNode) { "Controller" })
    }

    @Test
    fun `a known model is never asked for its product string`() {
        friendlyUsbName("Xbox 360 Controller", ::deviceNode) { error("read the product string") }
    }

    @Test
    fun `an unknown model is named by its product string`() {
        assertEquals("Controller", friendlyUsbName("", ::deviceNode) { "Controller" })
    }

    @Test
    fun `a known model never reads its device node`() {
        friendlyUsbName("Xbox 360 Controller", { error("read the device node") }) { "Controller" }
    }

    @Test
    fun `a model named by its product string never reads its device node`() {
        assertEquals("Controller", friendlyUsbName("", { error("read the device node") }) { "Controller" })
    }

    @Test
    fun `an unknown model with a blank product string is named by its device node`() {
        assertEquals(DEVICE_NODE, friendlyUsbName("", ::deviceNode) { "  " })
        assertEquals(DEVICE_NODE, friendlyUsbName("", ::deviceNode) { null })
    }

    private fun deviceNode(): String = DEVICE_NODE

    // UsbManager fills the device and the grant into the permission broadcast, which 31+ only
    // allows on a mutable PendingIntent; below 31 the flag does not exist.
    @Test
    fun `below API 31 the permission intent only updates in place`() {
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, usbPermissionIntentFlags(Build.VERSION_CODES.R))
    }

    @Test
    fun `from API 31 the permission intent is also mutable`() {
        val expected = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

        assertEquals(expected, usbPermissionIntentFlags(Build.VERSION_CODES.S))
    }

    private fun framework(
        id: Int,
        vendorId: Int = VID,
        productId: Int = PID,
        isUsbSynthetic: Boolean = false,
        transitioning: Boolean = false,
        needsReplug: Boolean = false,
        disconnectingTimeLeftSec: Int? = null,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad",
        disconnectingTimeLeftSec = disconnectingTimeLeftSec,
        isUsbSynthetic = isUsbSynthetic,
        transitioning = transitioning,
        needsReplug = needsReplug,
        vendorId = vendorId,
        productId = productId,
    )

    @Test
    fun `a settled framework device of the model is framework presence`() {
        assertEquals(7, liveFrameworkFor(mapOf(7 to framework(7)), VID, PID))
    }

    @Test
    fun `a device of another model is not framework presence`() {
        assertNull(liveFrameworkFor(mapOf(7 to framework(7, productId = PID + 1)), VID, PID))
    }

    @Test
    fun `a synthetic entry is not framework presence`() {
        assertNull(liveFrameworkFor(mapOf(-1000 to framework(-1000, isUsbSynthetic = true)), VID, PID))
    }

    @Test
    fun `a held placeholder is not framework presence`() {
        assertNull(liveFrameworkFor(mapOf(7 to framework(7, transitioning = true)), VID, PID))
    }

    @Test
    fun `a needs-replug card is not framework presence`() {
        assertNull(liveFrameworkFor(mapOf(7 to framework(7, needsReplug = true)), VID, PID))
    }

    @Test
    fun `a device on its disconnect countdown is not framework presence`() {
        assertNull(liveFrameworkFor(mapOf(7 to framework(7, disconnectingTimeLeftSec = 3)), VID, PID))
    }

    @Test
    fun `a live twin is found past a placeholder of the same model`() {
        val devices = mapOf(7 to framework(7, transitioning = true), 9 to framework(9))

        assertEquals(9, liveFrameworkFor(devices, VID, PID))
    }

    private companion object {
        const val VID = 0x045E
        const val PID = 0x028E
        const val DEVICE_NODE = "/dev/bus/usb/001/002"
    }
}
