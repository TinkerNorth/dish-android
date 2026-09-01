// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gets/sends chips on a destination card must cover every feature the
 * path can carry — a host that can light a pad or shape its triggers says so
 * on the picker card, not only in the type table.
 */
class DestinationFlowsTest {
    private val everything = CapabilitySet(Feature.entries.toSet())

    @Test
    fun `a fully capable destination lists every get`() {
        val labels = destinationGetFlows(everything).map { it.label }
        assertEquals(
            listOf(
                R.string.setup_cfg_flow_controller,
                R.string.binding_func_gyro,
                R.string.touchpad_mode_pad,
                R.string.touchpad_mode_mouse,
                R.string.setup_cap_battery,
                R.string.setup_cap_mic,
            ),
            labels,
        )
    }

    @Test
    fun `a fully capable destination lists every send`() {
        val labels = destinationSendFlows(everything).map { it.label }
        assertEquals(
            listOf(
                R.string.binding_func_rumble,
                R.string.setup_cap_trigger_rumble,
                R.string.setup_cap_lightbar,
                R.string.setup_cap_trigger_effects,
                R.string.setup_cap_player_leds,
                R.string.setup_cap_speaker,
            ),
            labels,
        )
    }

    @Test
    fun `every RECEIVE feature with a wire is represented in the sends row`() {
        // A newly added receive feature must get a chip, or the picker silently
        // under-describes the destination again.
        val receiveFeatures =
            Feature.entries.filter { it.direction == com.tinkernorth.dish.core.model.Direction.RECEIVE }
        assertEquals(receiveFeatures.size, destinationSendFlows(everything).size)
    }

    @Test
    fun `a bluetooth-shaped potential lists the pad and nothing back`() {
        val bt = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS)
        assertEquals(listOf(R.string.setup_cfg_flow_controller), destinationGetFlows(bt).map { it.label })
        assertTrue(destinationSendFlows(bt).isEmpty())
    }

    @Test
    fun `a moonlight-shaped potential carries battery up and trigger rumble back but no LED writes`() {
        val moonlight =
            CapabilitySet.of(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.MOTION,
                Feature.TOUCHPAD,
                Feature.MOUSE,
                Feature.BATTERY,
                Feature.RUMBLE,
                Feature.TRIGGER_RUMBLE,
                Feature.LIGHTBAR,
            )
        val gets = destinationGetFlows(moonlight).map { it.label }
        assertTrue(R.string.setup_cap_battery in gets)
        val sends = destinationSendFlows(moonlight).map { it.label }
        assertTrue(R.string.setup_cap_trigger_rumble in sends)
        assertTrue(R.string.setup_cap_lightbar in sends)
        assertFalse(R.string.setup_cap_trigger_effects in sends)
        assertFalse(R.string.setup_cap_player_leds in sends)
        // Moonlight has no controller-audio messages at all, in either direction.
        assertFalse(R.string.setup_cap_mic in destinationGetFlows(moonlight).map { it.label })
        assertFalse(R.string.setup_cap_speaker in sends)
    }

    @Test
    fun `a destination that carries only one audio direction says only that`() {
        // The two are independent on the wire, so the chips must not imply a pair.
        val micOnly = CapabilitySet.of(Feature.GAMEPAD, Feature.MIC)
        assertTrue(R.string.setup_cap_mic in destinationGetFlows(micOnly).map { it.label })
        assertTrue(destinationSendFlows(micOnly).isEmpty())

        val speakerOnly = CapabilitySet.of(Feature.GAMEPAD, Feature.SPEAKER)
        assertFalse(R.string.setup_cap_mic in destinationGetFlows(speakerOnly).map { it.label })
        assertEquals(listOf(R.string.setup_cap_speaker), destinationSendFlows(speakerOnly).map { it.label })
    }
}
