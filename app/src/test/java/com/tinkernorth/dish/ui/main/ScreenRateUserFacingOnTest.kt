// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRateUserFacingOnTest {
    @Test
    fun `the virtual slot always computes regardless of binding`() {
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, null, null))
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, ConnectionKind.BLUETOOTH, null))
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, ConnectionKind.SATELLITE, TouchpadModeValue.OFF))
    }

    @Test
    fun `a physical slot computes only with an enabled satellite touchpad surface`() {
        assertTrue(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, TouchpadModeValue.DS4))
        assertTrue(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, TouchpadModeValue.MOUSE))
    }

    @Test
    fun `a physical slot with the touchpad off does not compute`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, TouchpadModeValue.OFF))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, null))
    }

    @Test
    fun `a physical slot without a satellite binding does not compute`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.BLUETOOTH, TouchpadModeValue.DS4))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, null, TouchpadModeValue.DS4))
    }
}
