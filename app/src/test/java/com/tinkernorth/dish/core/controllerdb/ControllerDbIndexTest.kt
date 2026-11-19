// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDbIndexTest {
    private val pad = record("pad", usb = listOf("045e:028e"))
    private val bluePad = record("blue", bluetooth = listOf("28de:1106"), transport = TRANSPORT_BLUETOOTH)
    private val twin = record("twin", usb = listOf("045e:028e"), family = "xboxOneGip")

    private fun indexOf(
        vararg records: ControllerRecord,
        strings: Map<String, Map<String, String>> = emptyMap(),
    ): ControllerDbIndex = ControllerDbIndex(db(1, *records), ControllerStrings(strings, null, emptySet()))

    @Test
    fun `a usb identity finds its record`() {
        val index = indexOf(pad)
        assertEquals("pad", index.recordForUsb(0x045E, 0x028E)?.id)
    }

    @Test
    fun `a bluetooth identity finds its record and not a usb lookup`() {
        val index = indexOf(bluePad)
        assertEquals("blue", index.recordForBluetooth(0x28DE, 0x1106)?.id)
        assertNull(index.recordForUsb(0x28DE, 0x1106))
    }

    @Test
    fun `an unknown identity finds nothing`() {
        assertNull(indexOf(pad).recordForUsb(0x1234, 0x5678))
    }

    @Test
    fun `an identity two records list keeps both, the first one first`() {
        val index = indexOf(pad, twin)
        assertEquals(listOf("pad", "twin"), index.usbRecords("045e:028e").map { it.id })
        assertEquals("pad", index.recordForUsb(0x045E, 0x028E)?.id)
    }

    @Test
    fun `an identity key is lowercase four-digit hex`() {
        assertEquals("045e:028e", identityKey(0x045E, 0x028E))
        assertEquals("0001:000a", identityKey(1, 10))
    }

    @Test
    fun `a malformed identity parses to nothing`() {
        assertEquals(0x045E to 0x028E, parseIdentity("045e:028e"))
        assertNull(parseIdentity("045e"))
        assertNull(parseIdentity("zzzz:0000"))
        assertNull(parseIdentity("12345:0000"))
    }

    @Test
    fun `a record needing a family this build compiles is applicable`() {
        assertFalse(isInert(pad))
    }

    @Test
    fun `a record needing a family this build lacks is inert`() {
        assertTrue(isInert(record("future", family = "xinput360").requiring(listOf("gameCubeUsb"))))
    }

    @Test
    fun `a record whose protocol is a family this build lacks is inert`() {
        assertTrue(isInert(record("future", family = "gameCubeUsb").requiring(emptyList())))
    }

    @Test
    fun `a record needing an op this build lacks is inert`() {
        assertTrue(isInert(record("future").requiring(listOf("xinput360"), ops = listOf("templates"))))
    }

    @Test
    fun `an issue for this transport and device shows`() {
        val issue = RecordIssue(status = "open", id = "thor", device = "AYN Thor", transport = TRANSPORT_USB)
        val withIssue = record("pad", issues = listOf(issue))
        assertEquals(listOf("thor"), issuesFor(withIssue, TRANSPORT_USB, "AYN Thor", 15).map { it.id })
    }

    @Test
    fun `an issue for another device does not show`() {
        val issue = RecordIssue(status = "open", id = "thor", device = "AYN Thor", transport = TRANSPORT_USB)
        assertTrue(issuesFor(record("pad", issues = listOf(issue)), TRANSPORT_USB, "Pixel 9", 15).isEmpty())
    }

    @Test
    fun `an issue for another transport does not show`() {
        val issue = RecordIssue(status = "open", id = "thor", transport = TRANSPORT_BLUETOOTH)
        assertTrue(issuesFor(record("pad", issues = listOf(issue)), TRANSPORT_USB, "AYN Thor", 15).isEmpty())
    }

    @Test
    fun `an issue naming no device shows on every device`() {
        val issue = RecordIssue(status = "open", id = "all", transport = TRANSPORT_USB)
        assertEquals(1, issuesFor(record("pad", issues = listOf(issue)), TRANSPORT_USB, "Pixel 9", 15).size)
    }

    @Test
    fun `an issue for another android version does not show`() {
        val os = buildJsonObject { put("android", JsonPrimitive(15)) }
        val issue = RecordIssue(status = "open", id = "thor", transport = TRANSPORT_USB, os = os)
        val withIssue = record("pad", issues = listOf(issue))
        assertEquals(1, issuesFor(withIssue, TRANSPORT_USB, "AYN Thor", 15).size)
        assertTrue(issuesFor(withIssue, TRANSPORT_USB, "AYN Thor", 14).isEmpty())
    }

    @Test
    fun `an issue for another operating system does not show on android`() {
        val os = buildJsonObject { put("mac", JsonPrimitive(26)) }
        val issue = RecordIssue(status = "open", id = "mac-only", transport = TRANSPORT_USB, os = os)
        assertTrue(issuesFor(record("pad", issues = listOf(issue)), TRANSPORT_USB, "AYN Thor", 15).isEmpty())
    }

    @Test
    fun `an issue that is no longer open does not show`() {
        val issue = RecordIssue(status = "fixed", id = "thor", transport = TRANSPORT_USB)
        assertTrue(issuesFor(record("pad", issues = listOf(issue)), TRANSPORT_USB, "AYN Thor", 15).isEmpty())
    }

    @Test
    fun `an android version given as a string matches its release`() {
        val os = buildJsonObject { put("android", JsonPrimitive("15")) }
        val issue = RecordIssue(status = "open", id = "thor", transport = TRANSPORT_USB, os = os)
        assertEquals(1, issuesFor(record("pad", issues = listOf(issue)), TRANSPORT_USB, "AYN Thor", 15).size)
    }

    @Test
    fun `a mapping is read only as a string`() {
        assertEquals("switch", mappingName(record("hid", family = FAMILY_HID, mapping = "switch")))
        assertEquals(MAPPING_WESTERN, mappingName(record("hid", family = FAMILY_HID)))
    }
}
