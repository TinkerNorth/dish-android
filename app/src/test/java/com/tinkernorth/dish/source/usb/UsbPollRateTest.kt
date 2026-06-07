// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPollRateTest {
    @Test
    fun `full-speed 1ms interval is 1000 Hz`() {
        assertEquals(1000, computeUsbPollRateHz(epInterval = 1, epMaxPacketSize = 64))
    }

    @Test
    fun `full-speed 8ms interval is 125 Hz`() {
        assertEquals(125, computeUsbPollRateHz(epInterval = 8, epMaxPacketSize = 64))
    }

    @Test
    fun `full-speed 10ms interval is 100 Hz with a small packet`() {
        assertEquals(100, computeUsbPollRateHz(epInterval = 10, epMaxPacketSize = 20))
    }

    @Test
    fun `high-speed exponent 1 is 8000 Hz`() {
        assertEquals(8000, computeUsbPollRateHz(epInterval = 1, epMaxPacketSize = 65))
    }

    @Test
    fun `high-speed exponent 4 is 1000 Hz`() {
        assertEquals(1000, computeUsbPollRateHz(epInterval = 4, epMaxPacketSize = 512))
    }

    @Test
    fun `high-speed exponent 2 is 4000 Hz`() {
        assertEquals(4000, computeUsbPollRateHz(epInterval = 2, epMaxPacketSize = 512))
    }

    @Test
    fun `high-speed large max packet still decodes the exponent`() {
        assertEquals(8000, computeUsbPollRateHz(epInterval = 1, epMaxPacketSize = 1024))
    }

    @Test
    fun `packet size of exactly 64 is treated as full-speed`() {
        assertEquals(1000, computeUsbPollRateHz(epInterval = 1, epMaxPacketSize = 64))
    }

    @Test
    fun `packet size of 65 crosses into high-speed`() {
        assertEquals(8000, computeUsbPollRateHz(epInterval = 1, epMaxPacketSize = 65))
    }

    @Test
    fun `zero interval yields zero`() {
        assertEquals(0, computeUsbPollRateHz(epInterval = 0, epMaxPacketSize = 64))
    }

    @Test
    fun `negative interval yields zero`() {
        assertEquals(0, computeUsbPollRateHz(epInterval = -5, epMaxPacketSize = 64))
    }

    @Test
    fun `high-speed exponent is clamped so an absurd interval cannot overflow the shift`() {
        assertEquals(0, computeUsbPollRateHz(epInterval = 16, epMaxPacketSize = 128))
    }

    @Test
    fun `500 completions over 500 ms is 1000 Hz`() {
        assertEquals(1000, measuredPollRateHz(deltaCount = 500, deltaMs = 500))
    }

    @Test
    fun `125 completions over 1000 ms is 125 Hz`() {
        assertEquals(125, measuredPollRateHz(deltaCount = 125, deltaMs = 1000))
    }

    @Test
    fun `1000 completions over 500 ms is 2000 Hz`() {
        assertEquals(2000, measuredPollRateHz(deltaCount = 1000, deltaMs = 500))
    }

    @Test
    fun `no completions reports zero rather than the previous reading`() {
        assertEquals(0, measuredPollRateHz(deltaCount = 0, deltaMs = 500))
    }

    @Test
    fun `zero elapsed window yields zero`() {
        assertEquals(0, measuredPollRateHz(deltaCount = 500, deltaMs = 0))
    }

    @Test
    fun `negative elapsed window yields zero`() {
        assertEquals(0, measuredPollRateHz(deltaCount = 500, deltaMs = -10))
    }

    @Test
    fun `negative count delta from a counter reset yields zero not a negative rate`() {
        assertEquals(0, measuredPollRateHz(deltaCount = -995, deltaMs = 500))
    }

    @Test
    fun `rate is integer-floored`() {
        assertEquals(1500, measuredPollRateHz(deltaCount = 3, deltaMs = 2))
        assertEquals(333, measuredPollRateHz(deltaCount = 1, deltaMs = 3))
    }
}
