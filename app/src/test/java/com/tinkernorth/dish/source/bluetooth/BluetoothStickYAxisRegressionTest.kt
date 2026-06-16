// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.buildHidReport
import com.tinkernorth.dish.ui.common.computeStickAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothStickYAxisRegressionTest {
    private fun le16(
        lo: Byte,
        hi: Byte,
    ): Int = (((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)).toShort().toInt()

    private fun hidLeftY(report: ByteArray): Int = le16(report[6], report[7])

    private fun hidRightY(report: ByteArray): Int = le16(report[10], report[11])

    @Test
    fun `virtual stick pushed up sends HID stick-up (negative Y) on both sticks`() {
        val up = computeStickAxes(0f, -1f)
        val report = buildHidReport(0, 0, up.axisX, up.axisY, up.axisX, up.axisY, 0, 0)
        assertTrue("left HID Y must be negative for stick-up, was ${hidLeftY(report)}", hidLeftY(report) < 0)
        assertTrue("right HID Y must be negative for stick-up, was ${hidRightY(report)}", hidRightY(report) < 0)
    }

    @Test
    fun `virtual stick pulled down sends HID stick-down (positive Y)`() {
        val down = computeStickAxes(0f, 1f)
        val report = buildHidReport(0, 0, down.axisX, down.axisY, 0, 0, 0, 0)
        assertTrue("left HID Y must be positive for stick-down, was ${hidLeftY(report)}", hidLeftY(report) > 0)
    }

    @Test
    fun `virtual stick centered sends HID Y zero`() {
        val neutral = computeStickAxes(0f, 0f)
        val report = buildHidReport(0, 0, neutral.axisX, neutral.axisY, 0, 0, 0, 0)
        assertEquals(0, hidLeftY(report))
    }

    @Test
    fun `horizontal axis is unchanged - finger right stays HID X positive`() {
        val right = computeStickAxes(1f, 0f)
        val report = buildHidReport(0, 0, right.axisX, right.axisY, 0, 0, 0, 0)
        assertTrue("left HID X must stay positive for stick-right", le16(report[4], report[5]) > 0)
        assertEquals("Y must be centered", 0, hidLeftY(report))
    }
}
