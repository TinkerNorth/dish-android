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

    // ── controller audio: two independent rows, one of them permission-gated ──

    @Test
    fun `each audio row opens on its own feature`() {
        assertTrue(stateWith(Feature.MIC).micAvailable)
        assertFalse(stateWith(Feature.MIC).speakerAvailable)
        assertTrue(stateWith(Feature.SPEAKER).speakerAvailable)
        assertFalse(stateWith(Feature.SPEAKER).micAvailable)
        assertFalse(stateWith().micAvailable)
        assertFalse(stateWith().speakerAvailable)
    }

    @Test
    fun `the mic row asks for permission only when it is on and the grant is missing`() {
        val draft = BindingDraft(hostId = "sat-A", type = 2, directOn = false, motionOn = false)
        val on = ConfigUiState(capabilities = capsWith(Feature.MIC), draft = draft.copy(micOn = true))

        assertTrue(on.micNeedsPermission)
        // Granted: nothing to ask for.
        assertFalse(on.copy(micPermissionGranted = true).micNeedsPermission)
        // Switched off: nothing to ask for either, even without the grant.
        assertFalse(on.copy(draft = draft.copy(micOn = false)).micNeedsPermission)
    }

    @Test
    fun `a path with no microphone never asks for permission`() {
        // The row is not even shown, so a stale "on" from another binding must stay quiet.
        val draft = BindingDraft(hostId = "sat-A", type = 2, directOn = false, motionOn = false, micOn = true)
        val noMic = ConfigUiState(capabilities = capsWith(Feature.SPEAKER), draft = draft)
        assertFalse(noMic.micNeedsPermission)
    }
}
