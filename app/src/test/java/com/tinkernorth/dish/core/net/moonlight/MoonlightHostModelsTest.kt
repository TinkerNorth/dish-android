// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightHostModelsTest {
    @Test
    fun `host id prefers the stable uniqueid over the address`() {
        assertEquals("moonlight:uid:abc123", MoonlightHost.idFor("192.168.1.5", "abc123"))
        assertEquals("moonlight:192.168.1.5", MoonlightHost.idFor("192.168.1.5", ""))
    }

    @Test
    fun `remembered host round-trips to a host`() {
        val remembered =
            RememberedMoonlight(
                id = "moonlight:uid:x",
                name = "PC",
                address = "10.0.0.9",
                httpsPort = 47984,
                uniqueId = "x",
                lastAppId = "42",
                emulatedType = MoonlightEmulatedType.PLAYSTATION,
            )
        val host = remembered.toHost()
        assertEquals("PC", host.name)
        assertEquals("10.0.0.9", host.address)
        assertEquals("x", host.uniqueId)
        assertEquals(remembered.id, host.id)
    }

    @Test
    fun `emulated Auto resolves to a concrete arrival type, explicit passes through`() {
        assertEquals(MoonlightControlProtocol.CONTROLLER_TYPE_XBOX, MoonlightEmulatedType.resolve(MoonlightEmulatedType.AUTO))
        assertEquals(
            MoonlightControlProtocol.CONTROLLER_TYPE_PS,
            MoonlightEmulatedType.resolve(MoonlightEmulatedType.PLAYSTATION),
        )
        assertTrue(MoonlightEmulatedType.AUTO == 0xFF)
    }
}
