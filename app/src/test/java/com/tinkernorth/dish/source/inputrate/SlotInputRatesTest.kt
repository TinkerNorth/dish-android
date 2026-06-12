// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotInputRatesTest {
    @Test
    fun `empty rates have nothing to publish`() {
        assertFalse(SlotInputRates().hasAny)
    }

    @Test
    fun `any single measurement makes the rates publishable`() {
        assertTrue(SlotInputRates(controllerHz = 120).hasAny)
        assertTrue(SlotInputRates(controllerPeakHz = 120).hasAny)
        assertTrue(SlotInputRates(gyroHz = 250).hasAny)
    }
}
