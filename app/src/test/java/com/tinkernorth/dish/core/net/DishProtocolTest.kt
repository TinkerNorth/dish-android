// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DishProtocolTest {
    @Test
    fun `no advertisement reads as unknown, not as a verdict`() {
        assertEquals(DishProtocol.Compat.UNKNOWN, DishProtocol.compatFor(null))
        assertEquals(DishProtocol.Compat.UNKNOWN, DishProtocol.compatFor(0))
        assertEquals(DishProtocol.Compat.UNKNOWN, DishProtocol.compatFor(-1))
    }

    @Test
    fun `an older satellite still works but should update`() {
        assertEquals(
            DishProtocol.Compat.SATELLITE_UPDATE_AVAILABLE,
            DishProtocol.compatFor(DishProtocol.CURRENT - 1),
        )
    }

    @Test
    fun `the current version is current`() {
        assertEquals(DishProtocol.Compat.CURRENT, DishProtocol.compatFor(DishProtocol.CURRENT))
    }

    @Test
    fun `a newer satellite means this app must update`() {
        assertEquals(
            DishProtocol.Compat.APP_UPDATE_REQUIRED,
            DishProtocol.compatFor(DishProtocol.CURRENT + 1),
        )
    }

    @Test
    fun `an unknown satellite gets the optimistic current offer`() {
        assertEquals(DishProtocol.CURRENT, DishProtocol.speakFor(null))
        assertEquals(DishProtocol.CURRENT, DishProtocol.speakFor(0))
    }

    @Test
    fun `a known older satellite gets its own version offered`() {
        assertEquals(DishProtocol.MIN, DishProtocol.speakFor(DishProtocol.MIN))
    }

    @Test
    fun `a newer satellite is offered the best this client speaks`() {
        assertEquals(DishProtocol.CURRENT, DishProtocol.speakFor(DishProtocol.CURRENT + 3))
    }

    @Test
    fun `min and current bound the speakable range`() {
        for (v in DishProtocol.MIN..DishProtocol.CURRENT) {
            assertEquals(v, DishProtocol.speakFor(v))
        }
    }
}
