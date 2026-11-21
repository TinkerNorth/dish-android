// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.core.controllerdb.ControllerStrings
import com.tinkernorth.dish.core.controllerdb.LoadedControllerDb
import com.tinkernorth.dish.core.controllerdb.RecordIssue
import com.tinkernorth.dish.core.controllerdb.TRANSPORT_BLUETOOTH
import com.tinkernorth.dish.core.controllerdb.TRANSPORT_USB
import com.tinkernorth.dish.core.controllerdb.activeControllerDb
import com.tinkernorth.dish.core.controllerdb.db
import com.tinkernorth.dish.core.controllerdb.record
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.usb.UsbHardwareFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDiagMappersTest {
    private val issue = RecordIssue(id = "thor", device = "AYN Thor", transport = TRANSPORT_USB, status = "open")
    private val pad = record("sony-dualsense", usb = listOf("054c:0ce6"), family = "dualsense", issues = listOf(issue))
    private val blue = record("steam-ble", bluetooth = listOf("28de:1106"), transport = TRANSPORT_BLUETOOTH)
    private val strings =
        mapOf(
            "en" to
                mapOf(
                    "controller.sony-dualsense.name" to "Sony DualSense",
                    "issue.thor.title" to "No rumble",
                    "issue.thor.body" to "Being investigated.",
                ),
        )
    private val active = activeControllerDb(LoadedControllerDb(db(1, pad, blue), strings), null)
    private val world = DbWorld(active = active, stringsLocale = "de", deviceModel = "AYN Thor", androidMajor = 15)

    private fun device(
        vid: Int = 0x054C,
        pid: Int = 0x0CE6,
        transport: Transport = Transport.Usb,
        direct: Boolean = true,
    ) = PhysicalGamepadRegistry.Device(
        id = -5,
        name = "pad",
        vendorId = vid,
        productId = pid,
        transport = transport,
        isUsbSynthetic = direct,
    )

    private fun hardware(out: Int?) =
        UsbHardwareFacts(0x054C, 0x0CE6, "1.00", null, null, emptyList(), endpointIn = 0x84, endpointOut = out)

    @Test
    fun `a pad's record carries its id, its name by locale fallback, its state and this phone's issues`() {
        val facts = requireNotNull(recordFactsFor(device(), world))
        assertEquals("sony-dualsense", facts.id)
        assertEquals("Sony DualSense", facts.name)
        assertEquals("verified", facts.verification)
        assertEquals(listOf(IssueFacts("thor", "No rumble", "Being investigated.")), facts.issues)
    }

    @Test
    fun `an issue for another phone is not on the card`() {
        val facts = requireNotNull(recordFactsFor(device(), world.copy(deviceModel = "Pixel 9")))
        assertTrue(facts.issues.isEmpty())
    }

    @Test
    fun `a pad over bluetooth is found by its bluetooth identity`() {
        assertEquals("steam-ble", recordFactsFor(device(0x28DE, 0x1106, Transport.Bluetooth), world)?.id)
    }

    @Test
    fun `a pad over bluetooth with no bluetooth identity is found as its usb model`() {
        assertEquals("sony-dualsense", recordFactsFor(device(transport = Transport.Bluetooth), world)?.id)
    }

    @Test
    fun `an unknown pad and an unloaded database have no record`() {
        assertNull(recordFactsFor(device(0x1234, 0x5678), world))
        assertNull(recordFactsFor(device(), DbWorld()))
    }

    @Test
    fun `a direct pad missing its expected out endpoint shows the mismatch`() {
        assertEquals(listOf("endpoints.out: expected true, found false"), expectMismatchesFor(device(), active, hardware(null)))
    }

    @Test
    fun `a direct pad that matches its record has no mismatch`() {
        assertTrue(expectMismatchesFor(device(), active, hardware(0x03)).isEmpty())
    }

    @Test
    fun `a pad on the framework path or not yet enumerated is not compared`() {
        assertTrue(expectMismatchesFor(device(direct = false), active, hardware(null)).isEmpty())
        assertTrue(expectMismatchesFor(device(), active, null).isEmpty())
    }

    @Test
    fun `the strings unused here still resolve to null`() {
        assertNull(ControllerStrings(emptyMap(), null, emptySet()).controllerName("x", "en"))
    }
}
