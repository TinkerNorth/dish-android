// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DishProtocolTest {
    @Test
    fun `no advertisement reads as unknown, not as a verdict`() {
        assertEquals(DishProtocol.DishProtocolCompat.UNKNOWN, DishProtocol.dishProtocolCompatFor(null))
        assertEquals(DishProtocol.DishProtocolCompat.UNKNOWN, DishProtocol.dishProtocolCompatFor(0))
        assertEquals(DishProtocol.DishProtocolCompat.UNKNOWN, DishProtocol.dishProtocolCompatFor(-1))
    }

    @Test
    fun `an older satellite still works but should update`() {
        assertEquals(
            DishProtocol.DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE,
            DishProtocol.dishProtocolCompatFor(DishProtocol.DISH_PROTOCOL_CURRENT - 1),
        )
    }

    @Test
    fun `the current version is current`() {
        assertEquals(DishProtocol.DishProtocolCompat.CURRENT, DishProtocol.dishProtocolCompatFor(DishProtocol.DISH_PROTOCOL_CURRENT))
    }

    @Test
    fun `a newer satellite means this app must update`() {
        assertEquals(
            DishProtocol.DishProtocolCompat.APP_UPDATE_REQUIRED,
            DishProtocol.dishProtocolCompatFor(DishProtocol.DISH_PROTOCOL_CURRENT + 1),
        )
    }

    @Test
    fun `an unknown satellite gets the optimistic current offer`() {
        assertEquals(DishProtocol.DISH_PROTOCOL_CURRENT, DishProtocol.dishProtocolSpeakFor(null))
        assertEquals(DishProtocol.DISH_PROTOCOL_CURRENT, DishProtocol.dishProtocolSpeakFor(0))
    }

    @Test
    fun `a known older satellite gets its own version offered`() {
        assertEquals(DishProtocol.DISH_PROTOCOL_MIN, DishProtocol.dishProtocolSpeakFor(DishProtocol.DISH_PROTOCOL_MIN))
    }

    @Test
    fun `a newer satellite is offered the best this client speaks`() {
        assertEquals(DishProtocol.DISH_PROTOCOL_CURRENT, DishProtocol.dishProtocolSpeakFor(DishProtocol.DISH_PROTOCOL_CURRENT + 3))
    }

    @Test
    fun `min and current bound the speakable range`() {
        for (v in DishProtocol.DISH_PROTOCOL_MIN..DishProtocol.DISH_PROTOCOL_CURRENT) {
            assertEquals(v, DishProtocol.dishProtocolSpeakFor(v))
        }
    }
}
