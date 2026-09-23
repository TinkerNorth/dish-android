// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureTableTest {
    @Test
    fun `input rides out and feedback rides in, from the phone's perspective`() {
        val sends = Feature.entries.filter { it.direction == Direction.SEND }.toSet()
        assertEquals(
            setOf(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.MOTION,
                Feature.TOUCHPAD,
                Feature.MOUSE,
                Feature.KEYBOARD,
                Feature.BATTERY,
                Feature.MIC,
            ),
            sends,
        )
        assertEquals(Feature.entries.toSet() - sends, Feature.entries.filter { it.direction == Direction.RECEIVE }.toSet())
    }

    @Test
    fun `host-injected features carry no per-type catalog slug, because the host layer is their only gate`() {
        assertNull(Feature.MOUSE.catalogSlug)
        assertNull(Feature.KEYBOARD.catalogSlug)
    }

    @Test
    fun `battery has no catalog slug, because the satellite always accepts it`() {
        assertNull(Feature.BATTERY.catalogSlug)
    }

    @Test
    fun `trigger rumble has no catalog slug, because no satellite backend can source it`() {
        assertNull(Feature.TRIGGER_RUMBLE.catalogSlug)
        assertEquals(Direction.RECEIVE, Feature.TRIGGER_RUMBLE.direction)
    }

    @Test
    fun `gamepad itself is unconditional, so it carries no slug`() {
        assertNull(Feature.GAMEPAD.catalogSlug)
    }

    @Test
    fun `every other feature names the catalog slug the satellite gates it by`() {
        val slugs = Feature.entries.filter { it.catalogSlug != null }.associate { it to it.catalogSlug }
        assertEquals(
            mapOf(
                Feature.ANALOG_TRIGGERS to "analogTriggers",
                Feature.MOTION to "motion",
                Feature.TOUCHPAD to "touchpad",
                Feature.RUMBLE to "rumble",
                Feature.LIGHTBAR to "lightbar",
                Feature.TRIGGER_EFFECTS to "triggerEffects",
                Feature.PLAYER_LEDS to "playerLeds",
                Feature.MIC to "mic",
                Feature.SPEAKER to "speaker",
                Feature.HAPTIC_AUDIO to "hapticAudio",
            ),
            slugs,
        )
    }

    @Test
    fun `a slug is never shared, so a catalog entry resolves to one feature`() {
        val slugs = Feature.entries.mapNotNull { it.catalogSlug }
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun `mic and speaker are independent directions, neither implying the other`() {
        assertEquals(Direction.SEND, Feature.MIC.direction)
        assertEquals(Direction.RECEIVE, Feature.SPEAKER.direction)
    }
}
