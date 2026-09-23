// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadModeValueTest {
    @Test
    fun `every declared mode is valid`() {
        assertTrue(isValidTouchpadMode(TOUCHPAD_MODE_OFF))
        assertTrue(isValidTouchpadMode(TOUCHPAD_MODE_DS4))
        assertTrue(isValidTouchpadMode(TOUCHPAD_MODE_MOUSE))
    }

    @Test
    fun `TOUCHPAD_MODES holds exactly the declared modes, in wire order`() {
        assertEquals(listOf(TOUCHPAD_MODE_OFF, TOUCHPAD_MODE_DS4, TOUCHPAD_MODE_MOUSE), TOUCHPAD_MODES)
    }

    @Test
    fun `an unknown string is not a mode`() {
        assertFalse(isValidTouchpadMode("mouse2"))
        assertFalse(isValidTouchpadMode(""))
    }

    @Test
    fun `matching is case-sensitive, because the stored value is the wire value`() {
        assertFalse(isValidTouchpadMode("OFF"))
        assertFalse(isValidTouchpadMode("Ds4"))
    }

    @Test
    fun `null is not a mode`() {
        assertFalse(isValidTouchpadMode(null))
    }
}
