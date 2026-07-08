// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiBandTest {
    @Test
    fun `channel frequencies map to their marketing bands`() {
        assertEquals(WifiBand.GHZ_2_4, WifiBand.fromFrequencyMhz(2412)) // channel 1
        assertEquals(WifiBand.GHZ_2_4, WifiBand.fromFrequencyMhz(2484)) // channel 14
        assertEquals(WifiBand.GHZ_5, WifiBand.fromFrequencyMhz(5180)) // channel 36
        assertEquals(WifiBand.GHZ_5, WifiBand.fromFrequencyMhz(5825)) // channel 165
        assertEquals(WifiBand.GHZ_6, WifiBand.fromFrequencyMhz(5955)) // 6 GHz channel 1
        assertEquals(WifiBand.GHZ_6, WifiBand.fromFrequencyMhz(7115))
    }

    @Test
    fun `nonsense frequencies stay unknown instead of guessing`() {
        assertEquals(WifiBand.UNKNOWN, WifiBand.fromFrequencyMhz(0))
        assertEquals(WifiBand.UNKNOWN, WifiBand.fromFrequencyMhz(-1))
        assertEquals(WifiBand.UNKNOWN, WifiBand.fromFrequencyMhz(900))
    }
}
