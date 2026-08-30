// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class MoonlightLinkStateTest {
    @Test
    fun `live maps to Connected and launching to Connecting`() {
        assertEquals(LinkState.Connected, moonlightLinkState(MoonlightSessionState.Live, discovered = false))
        assertEquals(LinkState.Connecting, moonlightLinkState(MoonlightSessionState.Launching, discovered = true))
    }

    @Test
    fun `idle is Ready when discovered, Saved otherwise`() {
        assertEquals(LinkState.Ready, moonlightLinkState(MoonlightSessionState.Idle, discovered = true))
        assertEquals(LinkState.Saved, moonlightLinkState(MoonlightSessionState.Idle, discovered = false))
        assertEquals(LinkState.Saved, moonlightLinkState(null, discovered = false))
    }
}
