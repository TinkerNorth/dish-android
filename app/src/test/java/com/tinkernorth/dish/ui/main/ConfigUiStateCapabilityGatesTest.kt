// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertEquals
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
    fun `motion present opens motion and pad, touchpad follows the section`() {
        val s = stateWith(Feature.MOTION, Feature.TOUCHPAD)
        assertTrue(s.motionAvailable)
        assertTrue(s.touchpadAvailable)
        assertTrue(s.padModeAvailable)
    }

    @Test
    fun `touchpad only opens the touchpad section and the pad mode but not motion or mouse`() {
        val s = stateWith(Feature.TOUCHPAD)
        assertFalse(s.motionAvailable)
        assertTrue(s.touchpadAvailable)
        assertTrue(s.padModeAvailable)
        assertFalse(s.mouseModeAvailable)
    }

    @Test
    fun `mouse only opens the touchpad section and the mouse mode but not the pad mode`() {
        val s = stateWith(Feature.MOUSE)
        assertFalse(s.motionAvailable)
        assertTrue(s.touchpadAvailable)
        assertFalse(s.padModeAvailable)
        assertTrue(s.mouseModeAvailable)
    }

    @Test
    fun `none leaves every gate closed`() {
        val s = stateWith()
        assertFalse(s.motionAvailable)
        assertFalse(s.touchpadAvailable)
        assertFalse(s.padModeAvailable)
        assertFalse(s.mouseModeAvailable)
    }

    @Test
    fun `sanitizer keeps a pick its capability carries`() {
        assertEquals(
            TouchpadModeValue.DS4,
            sanitizedTouchpadMode(TouchpadModeValue.DS4, capsWith(Feature.TOUCHPAD)),
        )
        assertEquals(
            TouchpadModeValue.MOUSE,
            sanitizedTouchpadMode(TouchpadModeValue.MOUSE, capsWith(Feature.MOUSE)),
        )
        assertEquals(
            TouchpadModeValue.OFF,
            sanitizedTouchpadMode(TouchpadModeValue.OFF, capsWith(Feature.TOUCHPAD, Feature.MOUSE)),
        )
    }

    @Test
    fun `sanitizer collapses a pick the candidate path cannot carry`() {
        assertEquals(
            TouchpadModeValue.OFF,
            sanitizedTouchpadMode(TouchpadModeValue.DS4, capsWith(Feature.MOUSE)),
        )
        assertEquals(
            TouchpadModeValue.OFF,
            sanitizedTouchpadMode(TouchpadModeValue.MOUSE, capsWith(Feature.TOUCHPAD)),
        )
    }

    @Test
    fun `sanitizer collapses an unknown mode string`() {
        assertEquals(
            TouchpadModeValue.OFF,
            sanitizedTouchpadMode("warp-drive", capsWith(Feature.TOUCHPAD, Feature.MOUSE)),
        )
    }
}
