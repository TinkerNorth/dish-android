// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class NsdServiceResolverTest {
    private val ipv4 = InetAddress.getByName("192.168.1.20")
    private val ipv6 = InetAddress.getByName("fe80::1")

    @Test
    fun `a dual stack host prefers its ipv4`() {
        assertEquals("192.168.1.20", preferIpv4(listOf(ipv6, ipv4)))
    }

    @Test
    fun `an ipv6 only host still yields its address`() {
        assertEquals(ipv6.hostAddress, preferIpv4(listOf(ipv6)))
    }

    @Test
    fun `a host with no address yields nothing`() {
        assertNull(preferIpv4(emptyList()))
    }
}
