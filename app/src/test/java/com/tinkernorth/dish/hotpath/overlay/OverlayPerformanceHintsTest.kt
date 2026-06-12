// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.view.InputDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPerformanceHintsTest {
    @Test
    fun `highest refresh with empty modes leaves the mode as is`() {
        val current = mode(modeId = 1, hz = 60f)
        assertEquals(0, highestRefreshRateModeId(emptyList(), current))
    }

    @Test
    fun `highest refresh with only the current mode leaves the mode as is`() {
        val current = mode(modeId = 1, hz = 60f)
        assertEquals(0, highestRefreshRateModeId(listOf(current), current))
    }

    @Test
    fun `higher refresh at the same resolution selects that mode`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val faster = mode(modeId = 2, width = 1080, height = 2400, hz = 120f)
        assertEquals(2, highestRefreshRateModeId(listOf(current, faster), current))
    }

    @Test
    fun `higher refresh at a different resolution never switches resolution`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val fasterOtherRes = mode(modeId = 2, width = 1440, height = 3200, hz = 120f)
        assertEquals(0, highestRefreshRateModeId(listOf(current, fasterOtherRes), current))
    }

    @Test
    fun `multiple higher refresh modes at the same resolution selects the fastest`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val ninety = mode(modeId = 2, width = 1080, height = 2400, hz = 90f)
        val oneTwenty = mode(modeId = 3, width = 1080, height = 2400, hz = 120f)
        assertEquals(3, highestRefreshRateModeId(listOf(current, ninety, oneTwenty), current))
    }

    @Test
    fun `a tie at the current rate leaves the mode as is`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val sameRate = mode(modeId = 2, width = 1080, height = 2400, hz = 60f)
        assertEquals(0, highestRefreshRateModeId(listOf(current, sameRate), current))
    }

    @Test
    fun `a candidate within the one hz epsilon does not trigger a switch`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val marginal = mode(modeId = 2, width = 1080, height = 2400, hz = 60.5f)
        assertEquals(0, highestRefreshRateModeId(listOf(current, marginal), current))
    }

    @Test
    fun `sixty to ninety clears the epsilon and selects the candidate`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val ninety = mode(modeId = 2, width = 1080, height = 2400, hz = 90f)
        assertEquals(2, highestRefreshRateModeId(listOf(current, ninety), current))
    }

    @Test
    fun `sixty to one twenty clears the epsilon and selects the candidate`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val oneTwenty = mode(modeId = 2, width = 1080, height = 2400, hz = 120f)
        assertEquals(2, highestRefreshRateModeId(listOf(current, oneTwenty), current))
    }

    @Test
    fun `ninety to one twenty clears the epsilon and selects the candidate`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 90f)
        val oneTwenty = mode(modeId = 2, width = 1080, height = 2400, hz = 120f)
        assertEquals(2, highestRefreshRateModeId(listOf(current, oneTwenty), current))
    }

    @Test
    fun `realistic mixed list picks the fastest same resolution mode not the faster other resolution`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 60f)
        val sameNinety = mode(modeId = 2, width = 1080, height = 2400, hz = 90f)
        val sameOneTwenty = mode(modeId = 3, width = 1080, height = 2400, hz = 120f)
        val otherOneFortyFour = mode(modeId = 4, width = 1440, height = 3200, hz = 144f)
        val modes = listOf(current, sameNinety, sameOneTwenty, otherOneFortyFour)
        assertEquals(3, highestRefreshRateModeId(modes, current))
    }

    @Test
    fun `already at the max same resolution rate leaves the mode as is despite faster other resolutions`() {
        val current = mode(modeId = 1, width = 1080, height = 2400, hz = 120f)
        val otherOneFortyFour = mode(modeId = 2, width = 1440, height = 3200, hz = 144f)
        assertEquals(0, highestRefreshRateModeId(listOf(current, otherOneFortyFour), current))
    }

    @Test
    fun `pure joystick source is a joystick motion source`() {
        assertTrue(isJoystickMotionSource(InputDevice.SOURCE_JOYSTICK))
    }

    @Test
    fun `joystick combined with gamepad is a joystick motion source`() {
        val source = InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD
        assertTrue(isJoystickMotionSource(source))
    }

    @Test
    fun `keyboard source is not a joystick motion source`() {
        assertFalse(isJoystickMotionSource(InputDevice.SOURCE_KEYBOARD))
    }

    @Test
    fun `gamepad source alone is not a joystick motion source`() {
        assertFalse(isJoystickMotionSource(InputDevice.SOURCE_GAMEPAD))
    }

    @Test
    fun `zero source is not a joystick motion source`() {
        assertFalse(isJoystickMotionSource(0))
    }

    @Test
    fun `unbuffered requested when joystick and not yet requested`() {
        assertTrue(shouldRequestUnbufferedJoystick(isJoystick = true, alreadyRequested = false))
    }

    @Test
    fun `unbuffered not requested again when already requested`() {
        assertFalse(shouldRequestUnbufferedJoystick(isJoystick = true, alreadyRequested = true))
    }

    @Test
    fun `unbuffered not requested when not a joystick and not requested`() {
        assertFalse(shouldRequestUnbufferedJoystick(isJoystick = false, alreadyRequested = false))
    }

    @Test
    fun `unbuffered not requested when not a joystick and already requested`() {
        assertFalse(shouldRequestUnbufferedJoystick(isJoystick = false, alreadyRequested = true))
    }

    private fun mode(
        modeId: Int,
        width: Int = 1080,
        height: Int = 2400,
        hz: Float,
    ): DisplayModeInfo = DisplayModeInfo(modeId = modeId, width = width, height = height, refreshRate = hz)
}
