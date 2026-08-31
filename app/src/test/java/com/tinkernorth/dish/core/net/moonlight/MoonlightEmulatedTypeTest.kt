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
        assertEquals(0xFF, MoonlightEmulatedType.AUTO)
        assertNotEquals(MoonlightControlProtocol.CONTROLLER_TYPE_UNKNOWN, MoonlightEmulatedType.AUTO)
        assertEquals(0x01, MoonlightEmulatedType.XBOX)
        assertEquals(0x02, MoonlightEmulatedType.PLAYSTATION)
        assertEquals(0x03, MoonlightEmulatedType.NINTENDO)
    }

    @Test
    fun `a previously persisted 0 migrates back to Auto on read`() {
        assertEquals(MoonlightEmulatedType.AUTO, MoonlightEmulatedType.fromStored(0))
        assertEquals(MoonlightEmulatedType.AUTO, MoonlightEmulatedType.fromStored(MoonlightEmulatedType.AUTO))
        assertEquals(MoonlightEmulatedType.XBOX, MoonlightEmulatedType.fromStored(MoonlightEmulatedType.XBOX))
        assertEquals(MoonlightEmulatedType.PLAYSTATION, MoonlightEmulatedType.fromStored(MoonlightEmulatedType.PLAYSTATION))
        assertEquals(MoonlightEmulatedType.NINTENDO, MoonlightEmulatedType.fromStored(MoonlightEmulatedType.NINTENDO))
    }

    @Test
    fun `Auto resolves to PlayStation with motion and Xbox without`() {
        assertEquals(
            MoonlightEmulatedType.PLAYSTATION,
            MoonlightEmulatedType.resolve(MoonlightEmulatedType.AUTO, sourceHasMotion = true),
        )
        assertEquals(
            MoonlightEmulatedType.XBOX,
            MoonlightEmulatedType.resolve(MoonlightEmulatedType.AUTO, sourceHasMotion = false),
        )
    }

    @Test
    fun `an explicit pick is never re-resolved, motion or not`() {
        listOf(MoonlightEmulatedType.XBOX, MoonlightEmulatedType.PLAYSTATION, MoonlightEmulatedType.NINTENDO)
            .forEach { picked ->
                assertEquals(picked, MoonlightEmulatedType.resolve(picked, sourceHasMotion = true))
                assertEquals(picked, MoonlightEmulatedType.resolve(picked, sourceHasMotion = false))
            }
    }

    @Test
    fun `only PlayStation may declare the touchpad, motion and LED surfaces`() {
        // Trigger rumble and battery describe the physical pad, so every type
        // may carry them on top of analog triggers + rumble.
        val base = 0x03 or MoonlightControlProtocol.CAP_TRIGGER_RUMBLE or MoonlightControlProtocol.CAP_BATTERY
        assertEquals(base, MoonlightEmulatedType.typeMaximum(MoonlightEmulatedType.XBOX))
        assertEquals(0xFF, MoonlightEmulatedType.typeMaximum(MoonlightEmulatedType.PLAYSTATION))
        assertEquals(base, MoonlightEmulatedType.typeMaximum(MoonlightEmulatedType.NINTENDO))
    }

    @Test
    fun `the declared bits are the type maximum intersected with what the source can deliver`() {
        val everything = 0xFF
        val base = 0x03 or MoonlightControlProtocol.CAP_TRIGGER_RUMBLE or MoonlightControlProtocol.CAP_BATTERY
        assertEquals(base, MoonlightEmulatedType.capabilityBits(MoonlightEmulatedType.XBOX, everything))
        assertEquals(base, MoonlightEmulatedType.capabilityBits(MoonlightEmulatedType.NINTENDO, everything))
        assertEquals(0xFF, MoonlightEmulatedType.capabilityBits(MoonlightEmulatedType.PLAYSTATION, everything))

        // A source with nothing but a gamepad declares nothing, whatever the type allows.
        assertEquals(0x00, MoonlightEmulatedType.capabilityBits(MoonlightEmulatedType.PLAYSTATION, 0x00))
        // A rumble-only source on a PlayStation type does not claim the motion it cannot send.
        assertEquals(
            MoonlightControlProtocol.CAP_RUMBLE,
            MoonlightEmulatedType.capabilityBits(MoonlightEmulatedType.PLAYSTATION, MoonlightControlProtocol.CAP_RUMBLE),
        )
    }

    @Test
    fun `the touchpad click button flag rides on the touchpad capability alone`() {
        assertEquals(0xFFFF, MoonlightEmulatedType.supportedButtons(0x03))
        assertEquals(
            0xFFFF or MoonlightControlProtocol.BTN_TOUCHPAD,
            MoonlightEmulatedType.supportedButtons(0x03 or MoonlightControlProtocol.CAP_TOUCHPAD),
        )
    }

    @Test
    fun `the picker order is Auto, Xbox, PlayStation, Nintendo`() {
        assertEquals(
            listOf(
                MoonlightEmulatedType.AUTO,
                MoonlightEmulatedType.XBOX,
                MoonlightEmulatedType.PLAYSTATION,
                MoonlightEmulatedType.NINTENDO,
            ),
            MoonlightEmulatedType.ORDER,
        )
    }
}
