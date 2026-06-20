// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigUiStateCapabilityGatesTest {
    private fun stateWith(vararg available: Feature): ConfigUiState =
        ConfigUiState(
            capabilities =
                SlotCapabilities(
                    controller = CapabilitySet.of(*available),
                    transport = CapabilitySet.of(*available),
                    type = CapabilitySet.of(*available),
                    host = CapabilitySet.of(*available),
                    userEnabled = CapabilitySet.EMPTY,
                    runtimeDown = CapabilitySet.EMPTY,
                ),
        )

    @Test
    fun `motion present opens motion and pad, touchpad follows the section`() {
        val s = stateWith(Feature.MOTION, Feature.TOUCHPAD)
        assertTrue(s.motionAvailable)
        assertTrue(s.touchpadAvailable)
        assertTrue(s.padModeAvailable)
    }

    @Test
    fun `touchpad only opens the touchpad section and the pad mode but not motion`() {
        val s = stateWith(Feature.TOUCHPAD)
        assertFalse(s.motionAvailable)
        assertTrue(s.touchpadAvailable)
        assertTrue(s.padModeAvailable)
    }

    @Test
    fun `mouse only opens the touchpad section but not the pad mode`() {
        val s = stateWith(Feature.MOUSE)
        assertFalse(s.motionAvailable)
        assertTrue(s.touchpadAvailable)
        assertFalse(s.padModeAvailable)
    }

    @Test
    fun `none leaves every gate closed`() {
        val s = stateWith()
        assertFalse(s.motionAvailable)
        assertFalse(s.touchpadAvailable)
        assertFalse(s.padModeAvailable)
    }
}
