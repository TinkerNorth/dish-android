// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import com.tinkernorth.dish.composer.BundleCapturedReport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerCaptureTest {
    @Test
    fun `the raw descriptors split into the device descriptor and the configuration after it`() {
        val raw = byteArrayOf(0x04, 0x01, 0x00, 0x02, 0x09, 0x02)
        assertEquals("04010002" to "0902", splitRawDescriptors(raw))
    }

    @Test
    fun `no raw descriptors split into nothing`() {
        assertEquals(null to null, splitRawDescriptors(null))
        assertEquals(null to null, splitRawDescriptors(ByteArray(0)))
    }

    @Test
    fun `a device descriptor claiming more than there is takes all of it`() {
        assertEquals("1201" to null, splitRawDescriptors(byteArrayOf(0x12, 0x01)))
    }

    @Test
    fun `captured reports take their ages back to wall-clock times`() {
        val reports = parseCapturedReports(Json, "[{\"ageMs\":40,\"hex\":\"0180\"},{\"ageMs\":10,\"hex\":\"0181\"}]", 1000)
        assertEquals(listOf(BundleCapturedReport(960, "0180"), BundleCapturedReport(990, "0181")), reports)
    }

    @Test
    fun `an unreadable capture is no reports`() {
        assertTrue(parseCapturedReports(Json, "", 1000).isEmpty())
        assertTrue(parseCapturedReports(Json, "{}", 1000).isEmpty())
    }
}
