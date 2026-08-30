// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host decides whether a media ping is a ping by counting its bytes, so
 * these tests are about length first and content second. See
 * [MoonlightMediaPing] for the dead zone between the two accepted sizes.
 */
class MoonlightMediaPingTest {
    // The payload a live Sunshine host handed out in a video SETUP reply. It
    // reads like hex and is not: these are sixteen ASCII characters.
    private val livePayload = "68A75BBEEEA86826"

    @Test
    fun `an SS_PING is the payload verbatim followed by the sequence number`() {
        // The sixteen characters as ASCII, then the sequence number little-endian.
        val expected = livePayload.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x07, 0x00, 0x00, 0x00)
        assertArrayEquals(expected, MoonlightMediaPing.ssPing(livePayload, sequence = 7))
    }

    @Test
    fun `an SS_PING is exactly twenty bytes`() {
        // Nineteen would be silently dropped by the host with no log line.
        assertEquals(MoonlightMediaPing.SS_PING_LEN, MoonlightMediaPing.ssPing(livePayload, sequence = 0).size)
    }

    @Test
    fun `the payload is never hex decoded`() {
        // Hex-decoding this payload yields eight bytes, which lands in the dead
        // zone between the legacy and modern forms and is discarded in silence.
        // That mistake read as "Initial Ping Timeout" for days.
        val ping = MoonlightMediaPing.ssPing(livePayload, sequence = 0)
        assertEquals(livePayload, String(ping, 0, MoonlightMediaPing.PAYLOAD_LEN, Charsets.US_ASCII))
    }

    @Test
    fun `a short payload is padded and a long one truncated to sixteen bytes`() {
        assertEquals(MoonlightMediaPing.SS_PING_LEN, MoonlightMediaPing.ssPing("short", sequence = 1).size)
        val long = MoonlightMediaPing.ssPing("0123456789ABCDEFTRAILING", sequence = 1)
        assertEquals(MoonlightMediaPing.SS_PING_LEN, long.size)
        assertEquals("0123456789ABCDEF", String(long, 0, MoonlightMediaPing.PAYLOAD_LEN, Charsets.US_ASCII))
    }

    @Test
    fun `a padded short payload leaves the sequence number where the host reads it`() {
        val ping = MoonlightMediaPing.ssPing("short", sequence = 0x01020304)
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), ping.copyOfRange(MoonlightMediaPing.PAYLOAD_LEN, ping.size))
    }

    @Test
    fun `the legacy ping is exactly four bytes`() {
        val legacy = MoonlightMediaPing.legacy()
        assertEquals(MoonlightMediaPing.LEGACY_LEN, legacy.size)
        assertEquals("PING", String(legacy, Charsets.US_ASCII))
    }

    @Test
    fun `a host that named no payload falls back to the legacy form`() {
        assertFalse(MoonlightMediaPing.usable(""))
        assertTrue(MoonlightMediaPing.usable(livePayload))
    }
}
