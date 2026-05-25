// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.REPORT_ID
import com.tinkernorth.dish.core.input.REPORT_SIZE
import com.tinkernorth.dish.core.input.buildHidReport
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothGamepadReportTest {
    @Test
    fun `report is 14 bytes total`() {
        val r = buildHidReport(0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(REPORT_SIZE, r.size)
        assertEquals(14, r.size)
    }

    @Test
    fun `byte 0 is report id 1`() {
        val r = buildHidReport(0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(REPORT_ID.toByte(), r[0])
        assertEquals(0x01.toByte(), r[0])
    }

    @Test
    fun `buttons live at bytes 1-2 little-endian`() {
        val r = buildHidReport(0xBEEF, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0xEF.toByte(), r[1])
        assertEquals(0xBE.toByte(), r[2])
    }

    @Test
    fun `hat lives at byte 3`() {
        val r = buildHidReport(0, 0x08, 0, 0, 0, 0, 0, 0)
        assertEquals(0x08.toByte(), r[3])
    }

    @Test
    fun `left stick X occupies bytes 4-5 little-endian`() {
        val r = buildHidReport(0, 0, 0x1234.toShort(), 0, 0, 0, 0, 0)
        assertEquals(0x34.toByte(), r[4])
        assertEquals(0x12.toByte(), r[5])
    }

    @Test
    fun `left stick Y occupies bytes 6-7 little-endian`() {
        val r = buildHidReport(0, 0, 0, 0x1234.toShort(), 0, 0, 0, 0)
        assertEquals(0x34.toByte(), r[6])
        assertEquals(0x12.toByte(), r[7])
    }

    @Test
    fun `right stick X occupies bytes 8-9 little-endian`() {
        val r = buildHidReport(0, 0, 0, 0, 0x1234.toShort(), 0, 0, 0)
        assertEquals(0x34.toByte(), r[8])
        assertEquals(0x12.toByte(), r[9])
    }

    @Test
    fun `right stick Y occupies bytes 10-11 little-endian`() {
        val r = buildHidReport(0, 0, 0, 0, 0, 0x1234.toShort(), 0, 0)
        assertEquals(0x34.toByte(), r[10])
        assertEquals(0x12.toByte(), r[11])
    }

    @Test
    fun `triggers occupy bytes 12 and 13`() {
        val r = buildHidReport(0, 0, 0, 0, 0, 0, 0x55, 0xAA)
        assertEquals(0x55.toByte(), r[12])
        assertEquals(0xAA.toByte(), r[13])
    }

    @Test
    fun `stick-up on left stick is Y = plus 32767 per Xbox convention`() {
        val r = buildHidReport(0, 0, 0, Short.MAX_VALUE, 0, 0, 0, 0)
        assertEquals(0xFF.toByte(), r[6])
        assertEquals(0x7F.toByte(), r[7])
    }

    @Test
    fun `stick-down on left stick is Y = minus 32767`() {
        val r = buildHidReport(0, 0, 0, (-32767).toShort(), 0, 0, 0, 0)
        assertEquals(0x01.toByte(), r[6])
        assertEquals(0x80.toByte(), r[7])
    }

    @Test
    fun `signed min short round-trips correctly (two's complement)`() {
        val r = buildHidReport(0, 0, Short.MIN_VALUE, 0, 0, 0, 0, 0)
        assertEquals(0x00.toByte(), r[4])
        assertEquals(0x80.toByte(), r[5])
    }

    @Test
    fun `button bits above 16 are dropped (u16 wire width)`() {
        val r = buildHidReport(0x1FFFF, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0xFF.toByte(), r[1])
        assertEquals(0xFF.toByte(), r[2])
    }

    @Test
    fun `trigger values above 255 are truncated to low byte`() {
        val r = buildHidReport(0, 0, 0, 0, 0, 0, 0x1FF, 0x300)
        assertEquals(0xFF.toByte(), r[12])
        assertEquals(0x00.toByte(), r[13])
    }

    @Test
    fun `neutral report is report id followed by zeros`() {
        val r = buildHidReport(0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(REPORT_ID.toByte(), r[0])
        for (i in 1 until r.size) assertEquals("byte $i should be 0", 0.toByte(), r[i])
    }

    @Test
    fun `hat encoding documents the 9 positions`() {
        // 0=neutral,1=N,2=NE,3=E,4=SE,5=S,6=SW,7=W,8=NW.
        for (code in 0..8) {
            val r = buildHidReport(0, code, 0, 0, 0, 0, 0, 0)
            assertEquals("hat=$code", code.toByte(), r[3])
        }
    }
}
