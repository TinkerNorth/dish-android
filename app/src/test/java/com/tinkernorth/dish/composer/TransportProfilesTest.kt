// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportProfilesTest {
    @Test
    fun `satellite carries every feature except trigger rumble`() {
        // Trigger rumble has no satellite wire message: no virtual-pad backend
        // has trigger motors to source it from.
        val caps = TransportProfiles.forKind(ConnectionKind.SATELLITE)
        assertEquals(Feature.entries.toSet() - Feature.TRIGGER_RUMBLE, caps.features)
    }

    @Test
    fun `bluetooth carries only the fixed HID gamepad surface`() {
        val caps = TransportProfiles.forKind(ConnectionKind.BLUETOOTH)
        assertTrue(Feature.GAMEPAD in caps)
        assertTrue(Feature.ANALOG_TRIGGERS in caps)
        assertFalse(Feature.MOTION in caps)
        assertFalse(Feature.RUMBLE in caps)
        assertFalse(Feature.TOUCHPAD in caps)
    }

    @Test
    fun `moonlight carries the pad, the pointer surfaces and feedback, but no keyboard`() {
        val caps = TransportProfiles.forKind(ConnectionKind.MOONLIGHT)
        assertTrue(Feature.GAMEPAD in caps)
        assertTrue(Feature.TOUCHPAD in caps)
        assertTrue(Feature.MOUSE in caps)
        assertTrue(Feature.RUMBLE in caps)
        assertFalse(Feature.KEYBOARD in caps)
    }
}
