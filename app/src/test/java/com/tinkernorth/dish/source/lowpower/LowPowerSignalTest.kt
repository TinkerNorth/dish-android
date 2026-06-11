// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.lowpower

import org.junit.Assert.assertEquals
import org.junit.Test

class LowPowerSignalTest {
    @Test
    fun `starts inactive and mirrors the setter`() {
        val signal = LowPowerSignal()
        assertEquals(false, signal.state.value)
        signal.setActive(true)
        assertEquals(true, signal.state.value)
        signal.setActive(false)
        assertEquals(false, signal.state.value)
    }
}
