// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRateUserFacingOnTest {
    private fun phone(mode: String) = TouchpadSlotUi(mode = mode, phoneSourced = true)

    private fun pad(mode: String) = TouchpadSlotUi(mode = mode, phoneSourced = false)

    @Test
    fun `the virtual slot always computes regardless of binding`() {
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, null, null))
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, ConnectionKind.BLUETOOTH, null))
        assertTrue(screenRateUserFacingOn(SlotInputType.VIRTUAL, ConnectionKind.SATELLITE, phone(TouchpadModeValue.OFF)))
    }

    @Test
    fun `a physical slot computes only with an openable phone touchpad surface`() {
        assertTrue(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, phone(TouchpadModeValue.DS4)))
        assertTrue(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, phone(TouchpadModeValue.MOUSE)))
    }

    @Test
    fun `a physical slot with the touchpad off does not compute`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, phone(TouchpadModeValue.OFF)))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, null))
    }

    @Test
    fun `a pad-sourced touchpad has no phone surface, so the screen rate stays off`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, pad(TouchpadModeValue.DS4)))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.SATELLITE, pad(TouchpadModeValue.MOUSE)))
    }

    @Test
    fun `a physical slot without a satellite binding does not compute`() {
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, ConnectionKind.BLUETOOTH, phone(TouchpadModeValue.DS4)))
        assertFalse(screenRateUserFacingOn(SlotInputType.PHYSICAL, null, phone(TouchpadModeValue.DS4)))
    }

    @Test
    fun `openable requires both the phone source and a non-off mode`() {
        assertTrue(phone(TouchpadModeValue.DS4).openable)
        assertTrue(phone(TouchpadModeValue.MOUSE).openable)
        assertFalse(phone(TouchpadModeValue.OFF).openable)
        assertFalse(pad(TouchpadModeValue.DS4).openable)
        assertFalse(pad(TouchpadModeValue.OFF).openable)
    }
}
