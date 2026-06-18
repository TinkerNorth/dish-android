// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadQuirksTest {
    @Test
    fun `nintendo controllers swap both face-button pairs`() {
        val q = resolveGamepadQuirk(0x057E)
        assertTrue(q and QUIRK_SWAP_AB != 0)
        assertTrue(q and QUIRK_SWAP_XY != 0)
    }

    @Test
    fun `xbox controllers get no quirk`() {
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x045E))
    }

    @Test
    fun `sony controllers get no quirk`() {
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x054C))
    }

    @Test
    fun `unknown vendor gets no quirk`() {
        assertEquals(QUIRK_NONE, resolveGamepadQuirk(0x0000))
    }
}
