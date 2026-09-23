// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// The client-side half of CONTROLLER_ARRIVAL: which type Auto becomes, what each type
// is allowed to declare, and the 0xFF sentinel that keeps Auto out of the wire values.
class MoonlightEmulatedTypeTest {
    @Test
    fun `Auto is 0xFF and never the wire value for unknown`() {
        assertEquals(0xFF, AUTO)
        assertNotEquals(CONTROLLER_TYPE_UNKNOWN, AUTO)
        assertEquals(0x01, XBOX)
        assertEquals(0x02, PLAYSTATION)
        assertEquals(0x03, NINTENDO)
    }

    @Test
    fun `a previously persisted 0 migrates back to Auto on read`() {
        assertEquals(AUTO, fromStored(0))
        assertEquals(AUTO, fromStored(AUTO))
        assertEquals(XBOX, fromStored(XBOX))
        assertEquals(PLAYSTATION, fromStored(PLAYSTATION))
        assertEquals(NINTENDO, fromStored(NINTENDO))
    }

    @Test
    fun `Auto resolves to PlayStation with motion and Xbox without`() {
        assertEquals(
            PLAYSTATION,
            resolveMoonlightEmulatedType(AUTO, sourceHasMotion = true),
        )
        assertEquals(
            XBOX,
            resolveMoonlightEmulatedType(AUTO, sourceHasMotion = false),
        )
    }

    @Test
    fun `an explicit pick is never re-resolved, motion or not`() {
        listOf(XBOX, PLAYSTATION, NINTENDO)
            .forEach { picked ->
                assertEquals(picked, resolveMoonlightEmulatedType(picked, sourceHasMotion = true))
                assertEquals(picked, resolveMoonlightEmulatedType(picked, sourceHasMotion = false))
            }
    }

    @Test
    fun `only PlayStation may declare the touchpad, motion and LED surfaces`() {
        // Trigger rumble and battery describe the physical pad, so every type
        // may carry them on top of analog triggers + rumble.
        val base = 0x03 or CAP_TRIGGER_RUMBLE or CAP_BATTERY
        assertEquals(base, typeMaximum(XBOX))
        assertEquals(0xFF, typeMaximum(PLAYSTATION))
        assertEquals(base, typeMaximum(NINTENDO))
    }

    @Test
    fun `the declared bits are the type maximum intersected with what the source can deliver`() {
        val everything = 0xFF
        val base = 0x03 or CAP_TRIGGER_RUMBLE or CAP_BATTERY
        assertEquals(base, capabilityBits(XBOX, everything))
        assertEquals(base, capabilityBits(NINTENDO, everything))
        assertEquals(0xFF, capabilityBits(PLAYSTATION, everything))

        // A source with nothing but a gamepad declares nothing, whatever the type allows.
        assertEquals(0x00, capabilityBits(PLAYSTATION, 0x00))
        // A rumble-only source on a PlayStation type does not claim the motion it cannot send.
        assertEquals(
            CAP_RUMBLE,
            capabilityBits(PLAYSTATION, CAP_RUMBLE),
        )
    }

    @Test
    fun `the touchpad click button flag rides on the touchpad capability alone`() {
        assertEquals(0xFFFF, supportedButtons(0x03))
        assertEquals(
            0xFFFF or BTN_TOUCHPAD,
            supportedButtons(0x03 or CAP_TOUCHPAD),
        )
    }

    @Test
    fun `the picker order is Auto, Xbox, PlayStation, Nintendo`() {
        assertEquals(
            listOf(
                AUTO,
                XBOX,
                PLAYSTATION,
                NINTENDO,
            ),
            ORDER,
        )
    }
}
