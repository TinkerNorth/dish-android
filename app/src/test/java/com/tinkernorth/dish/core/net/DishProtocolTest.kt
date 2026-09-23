// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DishProtocolTest {
    @Test
    fun `no advertisement reads as unknown, not as a verdict`() {
        assertEquals(DishProtocolCompat.UNKNOWN, dishProtocolCompatFor(null))
        assertEquals(DishProtocolCompat.UNKNOWN, dishProtocolCompatFor(0))
        assertEquals(DishProtocolCompat.UNKNOWN, dishProtocolCompatFor(-1))
    }

    @Test
    fun `an older satellite still works but should update`() {
        assertEquals(
            DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE,
            dishProtocolCompatFor(DISH_PROTOCOL_CURRENT - 1),
        )
    }

    @Test
    fun `the current version is current`() {
        assertEquals(DishProtocolCompat.CURRENT, dishProtocolCompatFor(DISH_PROTOCOL_CURRENT))
    }

    @Test
    fun `a newer satellite means this app must update`() {
        assertEquals(
            DishProtocolCompat.APP_UPDATE_REQUIRED,
            dishProtocolCompatFor(DISH_PROTOCOL_CURRENT + 1),
        )
    }

    @Test
    fun `an unknown satellite gets the optimistic current offer`() {
        assertEquals(DISH_PROTOCOL_CURRENT, dishProtocolSpeakFor(null))
        assertEquals(DISH_PROTOCOL_CURRENT, dishProtocolSpeakFor(0))
    }

    @Test
    fun `a known older satellite gets its own version offered`() {
        assertEquals(DISH_PROTOCOL_MIN, dishProtocolSpeakFor(DISH_PROTOCOL_MIN))
    }

    @Test
    fun `a newer satellite is offered the best this client speaks`() {
        assertEquals(DISH_PROTOCOL_CURRENT, dishProtocolSpeakFor(DISH_PROTOCOL_CURRENT + 3))
    }

    @Test
    fun `min and current bound the speakable range`() {
        for (v in DISH_PROTOCOL_MIN..DISH_PROTOCOL_CURRENT) {
            assertEquals(v, dishProtocolSpeakFor(v))
        }
    }
}
