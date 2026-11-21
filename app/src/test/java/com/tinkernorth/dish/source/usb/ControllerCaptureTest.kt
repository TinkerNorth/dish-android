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

    private fun stringDescriptor(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_16LE)
        return byteArrayOf((body.size + 2).toByte(), 0x03) + body
    }

    @Test
    fun `a string descriptor is its utf-16le text after the two byte header`() {
        assertEquals("Wireless Controller", decodeStringDescriptor(stringDescriptor("Wireless Controller")))
        assertEquals("Sony Interactive", decodeStringDescriptor(stringDescriptor("Sony Interactive") + byteArrayOf(0x55, 0x55)))
    }

    @Test
    fun `a string descriptor that is not one reads as no text`() {
        assertEquals(null, decodeStringDescriptor(ByteArray(0)))
        assertEquals(null, decodeStringDescriptor(byteArrayOf(0x02, 0x03)))
        assertEquals(null, decodeStringDescriptor(byteArrayOf(0x04, 0x02, 0x41, 0x00)))
    }

    // A device descriptor whose iManufacturer is 1, iProduct 2 and iSerialNumber 3.
    private val rawDevice =
        ByteArray(18).also {
            it[0] = 18
            it[1] = 1
            it[12] = 0x07
            it[13] = 0x01
            it[14] = 1
            it[15] = 2
            it[16] = 3
        }

    @Test
    fun `the capture's strings are text keyed by role, and the serial number is never asked for`() {
        val asked = mutableListOf<Int>()
        val strings =
            captureStringsOf(rawDevice) { index ->
                asked += index
                mapOf(1 to "Sony", 2 to "Wireless Controller", 3 to "SERIAL")[index]?.let(::stringDescriptor)
            }
        assertEquals(mapOf("manufacturer" to "Sony", "product" to "Wireless Controller"), strings)
        assertTrue(3 !in asked)
    }

    @Test
    fun `a role the pad does not report is null`() {
        val noProduct = rawDevice.copyOf().also { it[15] = 0 }
        val strings = captureStringsOf(noProduct) { stringDescriptor("Sony") }
        assertEquals(mapOf("manufacturer" to "Sony", "product" to null), strings)
        assertEquals(mapOf("manufacturer" to null, "product" to null), captureStringsOf(null) { stringDescriptor("x") })
    }

    @Test
    fun `bcdDevice is bytes 12 and 13 of the device descriptor, four lowercase hex digits`() {
        assertEquals("0107", bcdDeviceOf(rawDevice))
        assertEquals(
            "0221",
            bcdDeviceOf(
                rawDevice.copyOf().also {
                    it[12] = 0x21
                    it[13] = 0x02
                },
            ),
        )
        assertEquals(
            "a1b2",
            bcdDeviceOf(
                rawDevice.copyOf().also {
                    it[12] = 0xB2.toByte()
                    it[13] = 0xA1.toByte()
                },
            ),
        )
    }

    @Test
    fun `no device descriptor gives no bcdDevice`() {
        assertEquals(null, bcdDeviceOf(null))
        assertEquals(null, bcdDeviceOf(ByteArray(13)))
        assertEquals(null, bcdDeviceOf(rawDevice.copyOf().also { it[1] = 2 }))
    }

    // AOSP UsbDeviceDescriptor.getDeviceReleaseString (services/usb/java/com/android/server/usb/descriptors),
    // which UsbDevice.getVersion() returns:
    //   int hundredths = mDeviceRelease & 0xF;  int tenths = (mDeviceRelease & 0xF0) >> 4;
    //   int ones = (mDeviceRelease & 0xF00) >> 8;  int tens = (mDeviceRelease & 0xF000) >> 12;
    //   return String.format("%d.%d%d", tens * 10 + ones, tenths, hundredths);
    private fun aosp(release: Int): String =
        "%d.%d%d".format(((release and 0xF000) shr 12) * 10 + ((release and 0xF00) shr 8), (release and 0xF0) shr 4, release and 0xF)

    @Test
    fun `getVersion inverts to the descriptor's value for every release AOSP formats unambiguously`() {
        assertEquals("0107", bcdFromReleaseString("1.07"))
        assertEquals("0221", bcdFromReleaseString("2.21"))
        assertEquals("0000", bcdFromReleaseString("0.00"))
        assertEquals("0110", bcdFromReleaseString("1.10"))
        for (major in 0..9) {
            for (tenths in 0..9) {
                for (hundredths in 0..9) {
                    val release = (major shl 8) or (tenths shl 4) or hundredths
                    assertEquals("%04x".format(release), bcdFromReleaseString(aosp(release)))
                }
            }
        }
    }

    @Test
    fun `a release string that more than one descriptor value formats to is null`() {
        // 0x0A00 and 0x1000 both format as "10.00": a major of ten or more cannot be told apart.
        assertEquals(aosp(0x0A00), aosp(0x1000))
        assertEquals(null, bcdFromReleaseString("10.00"))
        assertEquals(null, bcdFromReleaseString("12.34"))
        // A nibble above nine formats as two digits, which no version string of this shape holds.
        assertEquals(null, bcdFromReleaseString("1.107"))
        assertEquals(null, bcdFromReleaseString("1.7"))
        assertEquals(null, bcdFromReleaseString(""))
        assertEquals(null, bcdFromReleaseString(null))
    }
}
