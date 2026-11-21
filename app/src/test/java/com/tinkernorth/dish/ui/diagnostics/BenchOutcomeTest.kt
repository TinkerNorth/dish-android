// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.store.BenchTest
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.TestOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchOutcomeTest {
    @Test
    fun `the lamp on and pulsing are asked about and turning it off is not`() {
        assertEquals(BenchTest.LAMP_ON, lampTestFor(MIC_LED_ON))
        assertEquals(BenchTest.LAMP_PULSE, lampTestFor(MIC_LED_PULSE))
        assertNull(lampTestFor(MIC_LED_OFF))
    }

    @Test
    fun `an outcome's test is found by its bundle name`() {
        assertEquals(BenchTest.TONE, benchTestOf("tone.play"))
        assertNull(benchTestOf("rumble.sideways"))
    }

    @Test
    fun `worked reads as worked and didNotWork as did not work`() {
        assertEquals(R.string.inspector_outcome_worked, outcomeResultLabel(TestOutcome("tone.play", "worked", 1)))
        assertEquals(R.string.inspector_outcome_failed, outcomeResultLabel(TestOutcome("rumble.weak", "didNotWork", 1)))
    }
}
