// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.model.capabilitySetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureBenchTest {
    private fun caps(vararg features: Feature) = SlotCapabilities.NONE.copy(controller = capabilitySetOf(*features))

    @Test
    fun `the virtual pad benches rumble, sound and microphone only`() {
        val bench = featureBenchFor(caps(Feature.LIGHTBAR, Feature.PLAYER_LEDS), virtual = true, lampReachable = false)
        assertTrue(bench.rumble)
        assertTrue(bench.speaker)
        assertTrue(bench.mic)
        assertFalse(bench.lightbar)
        assertFalse(bench.playerLeds)
        assertFalse(bench.triggerEffects)
        assertFalse(bench.triggerRumble)
        assertFalse(bench.micLed)
        assertFalse(bench.haptics)
    }

    @Test
    fun `haptics is offered where the pad's path composes haptic audio`() {
        val bench = featureBenchFor(caps(Feature.HAPTIC_AUDIO), virtual = false, lampReachable = false)
        assertTrue(bench.haptics)
        assertTrue(bench.anyAudio)
    }

    @Test
    fun `a pad with a speaker but no haptic lane is not offered the haptics test`() {
        assertFalse(featureBenchFor(caps(Feature.SPEAKER), virtual = false, lampReachable = false).haptics)
    }

    @Test
    fun `a physical pad benches exactly what its own input can actuate`() {
        val bench = featureBenchFor(caps(Feature.RUMBLE, Feature.LIGHTBAR, Feature.MIC), virtual = false, lampReachable = true)
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
    fun `a pad with a microphone route whose reports carry no lamp is not offered the lamp tests`() {
        val bench = featureBenchFor(caps(Feature.MIC), virtual = false, lampReachable = false)
        assertTrue(bench.mic)
        assertFalse(bench.micLed)
    }

    @Test
    fun `a pad whose reports carry the lamp is offered the lamp tests with no microphone route`() {
        val bench = featureBenchFor(caps(), virtual = false, lampReachable = true)
        assertFalse(bench.mic)
        assertTrue(bench.micLed)
    }

    @Test
    fun `the on-screen pad is never offered the lamp tests`() {
        assertFalse(featureBenchFor(caps(Feature.MIC), virtual = true, lampReachable = true).micLed)
    }

    @Test
    fun `no capabilities means an empty bench`() {
        val bench = featureBenchFor(null, virtual = false, lampReachable = false)
        assertEquals(FeatureBench.NONE, bench)
        assertFalse(bench.anyFeedback)
        assertFalse(bench.anyAudio)
    }

    @Test
    fun `the rigid trigger blocks arm both triggers at full force`() {
        val blocks = rigidTriggerBlocks()
        assertEquals(22, blocks.size)
        for (offset in listOf(0, 11)) {
            assertEquals(0x01, blocks[offset].toInt())
            assertEquals(0, blocks[offset + 1].toInt())
            assertEquals(0xFF, blocks[offset + 2].toInt() and 0xFF)
        }
    }
}
