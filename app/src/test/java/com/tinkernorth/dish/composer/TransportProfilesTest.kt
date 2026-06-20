// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportProfilesTest {
    @Test
    fun `satellite carries every feature`() {
        val caps = TransportProfiles.forKind(ConnectionKind.SATELLITE)
        assertEquals(Feature.entries.toSet(), caps.features)
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
}
