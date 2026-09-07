// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureBenchTest {
    private fun caps(vararg features: Feature) = SlotCapabilities.NONE.copy(controller = CapabilitySet.of(*features))

    @Test
    fun `the virtual pad benches rumble, sound and microphone only`() {
        val bench = FeatureBench.from(caps(Feature.LIGHTBAR, Feature.PLAYER_LEDS), virtual = true)
        assertTrue(bench.rumble)
        assertTrue(bench.speaker)
        assertTrue(bench.mic)
        assertFalse(bench.lightbar)
        assertFalse(bench.playerLeds)
        assertFalse(bench.triggerEffects)
        assertFalse(bench.triggerRumble)
        assertFalse(bench.micLed)
    }

    @Test
    fun `a physical pad benches exactly what its own input can actuate`() {
        val bench = FeatureBench.from(caps(Feature.RUMBLE, Feature.LIGHTBAR, Feature.MIC), virtual = false)
        assertTrue(bench.rumble)
        assertTrue(bench.lightbar)
        assertTrue(bench.mic)
        assertTrue(bench.micLed)
        assertFalse(bench.speaker)
        assertFalse(bench.playerLeds)
        assertFalse(bench.triggerRumble)
        assertTrue(bench.anyFeedback)
        assertTrue(bench.anyAudio)
    }

    @Test
    fun `no capabilities means an empty bench`() {
        val bench = FeatureBench.from(null, virtual = false)
        assertEquals(FeatureBench.NONE, bench)
        assertFalse(bench.anyFeedback)
        assertFalse(bench.anyAudio)
    }

    @Test
    fun `the rigid trigger blocks arm both triggers at full force`() {
        val blocks = InputInspectorViewModel.rigidTriggerBlocks()
        assertEquals(22, blocks.size)
        for (offset in listOf(0, 11)) {
            assertEquals(0x01, blocks[offset].toInt())
            assertEquals(0, blocks[offset + 1].toInt())
            assertEquals(0xFF, blocks[offset + 2].toInt() and 0xFF)
        }
    }
}
