// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRateUserFacingOnTest {
    private fun pointer(
        touchpad: Boolean,
        mouse: Boolean,
    ) = PointerSlotUi(mode = TouchpadModeValue.DS4, touchpadOpenable = touchpad, mouseOpenable = mouse)

    @Test
    fun `the virtual slot always computes regardless of binding`() {
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, null, null))
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, ConnectionKind.BLUETOOTH, null))
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, ConnectionKind.SATELLITE, pointer(touchpad = false, mouse = false)))
    }

    @Test
    fun `a physical slot computes with either openable phone surface`() {
        assertTrue(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, pointer(touchpad = true, mouse = false)))
        assertTrue(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, pointer(touchpad = false, mouse = true)))
    }

    @Test
    fun `a physical slot with no openable surface does not compute`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, pointer(touchpad = false, mouse = false)))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, null))
    }

    @Test
    fun `a physical slot without a satellite binding does not compute`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.BLUETOOTH, pointer(touchpad = true, mouse = true)))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, null, pointer(touchpad = true, mouse = true)))
    }

    @Test
    fun `anyOpenable is the or of the two surfaces`() {
        assertTrue(pointer(touchpad = true, mouse = false).anyOpenable)
        assertTrue(pointer(touchpad = false, mouse = true).anyOpenable)
        assertFalse(pointer(touchpad = false, mouse = false).anyOpenable)
    }
}
