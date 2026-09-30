// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiBandTest {
    @Test
    fun `channel frequencies map to their marketing bands`() {
        assertEquals(WifiBand.GHZ_2_4, wifiBandForFrequency(2412)) // channel 1
        assertEquals(WifiBand.GHZ_2_4, wifiBandForFrequency(2484)) // channel 14
        assertEquals(WifiBand.GHZ_5, wifiBandForFrequency(5180)) // channel 36
        assertEquals(WifiBand.GHZ_5, wifiBandForFrequency(5825)) // channel 165
        assertEquals(WifiBand.GHZ_6, wifiBandForFrequency(5955)) // 6 GHz channel 1
        assertEquals(WifiBand.GHZ_6, wifiBandForFrequency(7115))
    }

    @Test
    fun `nonsense frequencies stay unknown instead of guessing`() {
        assertEquals(WifiBand.UNKNOWN, wifiBandForFrequency(0))
        assertEquals(WifiBand.UNKNOWN, wifiBandForFrequency(-1))
        assertEquals(WifiBand.UNKNOWN, wifiBandForFrequency(900))
    }
}
