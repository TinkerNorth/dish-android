// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dashboard-card pill facts for the protocol-2 surfaces: everything active on
 * the bound path shows up, and the Moonlight pointer story surfaces too.
 */
class FeedbackPillsTest {
    private fun caps(vararg features: Feature): SlotCapabilities {
        val set = CapabilitySet(features.toSet())
        return SlotCapabilities(
            controller = set,
            transport = set,
            type = set,
            host = set,
            userEnabled = CapabilitySet.EMPTY,
            runtimeDown = CapabilitySet.EMPTY,
        )
    }

    private fun row(cap: SlotCapabilities) =
        ControllerAdapter.Row(
            slot = ControllerSlot(id = "slot", name = "Pad", inputType = SlotInputType.PHYSICAL),
            connections = emptyList(),
            motionCap = cap,
        )

    @Test
    fun `a Direct DualSense on a satellite lists all its feedback surfaces`() {
        val facts =
            feedbackFuncFacts(
                caps(Feature.LIGHTBAR, Feature.TRIGGER_EFFECTS, Feature.PLAYER_LEDS),
            )
        assertEquals(
            listOf(FeedbackPillFact.LIGHTBAR, FeedbackPillFact.TRIGGER_EFFECTS, FeedbackPillFact.PLAYER_LEDS),
            facts,
        )
    }

    @Test
    fun `a Direct Xbox One pad on a moonlight host lists trigger rumble`() {
        assertEquals(
            listOf(FeedbackPillFact.TRIGGER_RUMBLE),
            feedbackFuncFacts(caps(Feature.TRIGGER_RUMBLE, Feature.RUMBLE)),
        )
    }

    @Test
    fun `a plain pad lists no feedback pills`() {
        assertTrue(feedbackFuncFacts(caps(Feature.GAMEPAD, Feature.RUMBLE, Feature.MOTION)).isEmpty())
        assertTrue(feedbackFuncFacts(SlotCapabilities.NONE).isEmpty())
    }

    @Test
    fun `a layer-limited surface is not shown as active`() {
        // Available is the four-layer intersection: a lightbar-bearing pad on a
        // type without one must not claim the pill.
        val limited =
            SlotCapabilities(
                controller = CapabilitySet.of(Feature.LIGHTBAR),
                transport = CapabilitySet.of(Feature.LIGHTBAR),
                type = CapabilitySet.EMPTY,
                host = CapabilitySet.of(Feature.LIGHTBAR),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        assertTrue(feedbackFuncFacts(limited).isEmpty())
    }

    @Test
    fun `a moonlight slot with pad touch and mouse shows both pointer facts`() {
        val facts = moonlightPointerFacts(row(caps(Feature.TOUCHPAD, Feature.MOUSE)))
        assertEquals(listOf(PointerPillFact.PAD_ON, PointerPillFact.MOUSE_READY), facts)
    }

    @Test
    fun `a moonlight slot with neither shows no pointer facts`() {
        assertTrue(moonlightPointerFacts(row(caps(Feature.GAMEPAD))).isEmpty())
    }
}
