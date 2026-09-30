// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsInputTest {
    @Test
    fun `a typed IPv6 host is rejected as one before anything dials it`() {
        val result = parseTypedSatellite(host = " fd00::5 ", httpsPort = "9443", udpPort = "9876")
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = false,
                httpsPortInvalid = false,
                udpPortInvalid = false,
                hostIsIpv6 = true,
            ),
            result,
        )
    }

    @Test
    fun `a typed bracketed IPv6 host with a bad port is rejected on both counts`() {
        val result = parseTypedSatellite(host = "[fe80::1]", httpsPort = "0", udpPort = "9876")
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = false,
                httpsPortInvalid = true,
                udpPortInvalid = false,
                hostIsIpv6 = true,
            ),
            result,
        )
    }

    @Test
    fun `a typed host with two valid ports is accepted with its host trimmed`() {
        val result = parseTypedSatellite(host = " 10.0.0.9 ", httpsPort = "9443", udpPort = "9876")
        assertEquals(TypedSatelliteResult.Accepted(TypedSatellite("10.0.0.9", 9443, 9876)), result)
    }

    @Test
    fun `a blank host is rejected`() {
        val result = parseTypedSatellite(host = "   ", httpsPort = "9443", udpPort = "9876")
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = true,
                httpsPortInvalid = false,
                udpPortInvalid = false,
                hostIsIpv6 = false,
            ),
            result,
        )
    }

    @Test
    fun `a missing host is rejected the same way`() {
        val result = parseTypedSatellite(host = null, httpsPort = "9443", udpPort = "9876")
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = true,
                httpsPortInvalid = false,
                udpPortInvalid = false,
                hostIsIpv6 = false,
            ),
            result,
        )
    }

    @Test
    fun `a port that is not a number is rejected`() {
        val result = parseTypedSatellite(host = "pc", httpsPort = "nine", udpPort = "9876")
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = false,
                httpsPortInvalid = true,
                udpPortInvalid = false,
                hostIsIpv6 = false,
            ),
            result,
        )
    }

    @Test
    fun `a port outside the range is rejected`() {
        val result = parseTypedSatellite(host = "pc", httpsPort = "9443", udpPort = "65536")
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = false,
                httpsPortInvalid = false,
                udpPortInvalid = true,
                hostIsIpv6 = false,
            ),
            result,
        )
    }

    @Test
    fun `every field is marked in the same pass`() {
        val result = parseTypedSatellite(host = "", httpsPort = "0", udpPort = null)
        assertEquals(
            TypedSatelliteResult.Rejected(
                hostMissing = true,
                httpsPortInvalid = true,
                udpPortInvalid = true,
                hostIsIpv6 = false,
            ),
            result,
        )
    }

    @Test
    fun `both ends of the port range are accepted`() {
        assertEquals(1, parsePort("1"))
        assertEquals(65535, parsePort(" 65535 "))
    }

    @Test
    fun `a port of zero or above the range is refused`() {
        assertNull(parsePort("0"))
        assertNull(parsePort("65536"))
    }

    @Test
    fun `a missing or unparseable port is refused`() {
        assertNull(parsePort(null))
        assertNull(parsePort(""))
        assertNull(parsePort("9443a"))
    }

    @Test
    fun `an empty pin is refused`() {
        assertFalse(isValidPin(""))
        assertFalse(isValidPin(null))
    }

    @Test
    fun `a typed pin is accepted`() {
        assertTrue(isValidPin("1234"))
    }
}
