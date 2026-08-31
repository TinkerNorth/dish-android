// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigUiStateCapabilityGatesTest {
    private fun capsWith(vararg available: Feature): SlotCapabilities =
        SlotCapabilities(
            controller = CapabilitySet.of(*available),
            transport = CapabilitySet.of(*available),
            type = CapabilitySet.of(*available),
            host = CapabilitySet.of(*available),
            userEnabled = CapabilitySet.EMPTY,
            runtimeDown = CapabilitySet.EMPTY,
        )

    private fun stateWith(vararg available: Feature): ConfigUiState = ConfigUiState(capabilities = capsWith(*available))

    @Test
    fun `motion present opens the motion gate`() {
        assertTrue(stateWith(Feature.MOTION, Feature.TOUCHPAD).motionAvailable)
    }

    @Test
    fun `touchpad or mouse alone never open motion`() {
        assertFalse(stateWith(Feature.TOUCHPAD).motionAvailable)
        assertFalse(stateWith(Feature.MOUSE).motionAvailable)
    }

    @Test
    fun `none leaves the motion gate closed`() {
        assertFalse(stateWith().motionAvailable)
    }
}
