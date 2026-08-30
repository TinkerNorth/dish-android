// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsMoonlightDiscoveryTest {
    @Test
    fun `builds a host from an mDNS service with a uniqueid TXT record`() {
        val host =
            mdnsServiceToHost(
                serviceName = "living-room",
                hostAddress = "192.168.1.7",
                txt = mapOf("uniqueid" to "deadbeef".toByteArray()),
            )!!
        assertEquals("living-room", host.name)
        assertEquals("192.168.1.7", host.address)
        assertEquals("deadbeef", host.uniqueId)
    }

    @Test
    fun `falls back to the address as the name when the service name is empty`() {
        val host = mdnsServiceToHost("", "10.0.0.3", emptyMap())!!
        assertEquals("10.0.0.3", host.name)
        assertEquals("", host.uniqueId)
    }

    @Test
    fun `a service with no address resolves to null`() {
        assertNull(mdnsServiceToHost("name", null, emptyMap()))
    }
}
