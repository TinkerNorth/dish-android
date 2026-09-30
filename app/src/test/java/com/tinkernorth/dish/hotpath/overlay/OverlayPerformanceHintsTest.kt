// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.view.InputDevice
import android.view.MotionEvent
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

    // ---- which key and motion events belong to a pad ----

    @Test
    fun `a key from a known pad with a keyboard source is still a gamepad key`() {
        assertTrue(acceptsGamepadKey(InputDevice.SOURCE_KEYBOARD, deviceId = KNOWN_PAD, knownPadIds = setOf(KNOWN_PAD)))
    }

    @Test
    fun `a key from an unknown keyboard is not a gamepad key`() {
        assertFalse(acceptsGamepadKey(InputDevice.SOURCE_KEYBOARD, deviceId = STRANGER, knownPadIds = setOf(KNOWN_PAD)))
    }

    @Test
    fun `a gamepad-sourced key from a device the registry has not listed yet is a gamepad key`() {
        assertTrue(acceptsGamepadKey(InputDevice.SOURCE_GAMEPAD, deviceId = STRANGER, knownPadIds = emptySet()))
    }

    @Test
    fun `a joystick-sourced key is a gamepad key`() {
        assertTrue(acceptsGamepadKey(InputDevice.SOURCE_JOYSTICK, deviceId = STRANGER, knownPadIds = emptySet()))
    }

    @Test
    fun `motion from a known pad with a foreign source is a joystick event`() {
        assertTrue(isJoystickEvent(InputDevice.SOURCE_MOUSE) { true })
    }

    @Test
    fun `motion from an unknown mouse is not a joystick event`() {
        assertFalse(isJoystickEvent(InputDevice.SOURCE_MOUSE) { false })
    }

    @Test
    fun `joystick-sourced motion is a joystick event whatever the registry holds`() {
        assertTrue(isJoystickEvent(InputDevice.SOURCE_JOYSTICK) { false })
    }

    // The 250 Hz path reads the registry only when the source bits leave the question open.
    @Test
    fun `joystick-sourced motion never asks the registry`() {
        var registryReads = 0

        isJoystickEvent(InputDevice.SOURCE_JOYSTICK) { ++registryReads > 0 }

        assertEquals(0, registryReads)
    }

    @Test
    fun `motion with a foreign source asks the registry once`() {
        var registryReads = 0

        isJoystickEvent(InputDevice.SOURCE_MOUSE) { ++registryReads > 0 }

        assertEquals(1, registryReads)
    }

    // ---- what re-arms the inactivity dim ----

    @Test
    fun `a touch re-arms the inactivity timer while the screen is kept on`() {
        assertTrue(shouldResetInactivity(keepScreenOn = true, actionMasked = MotionEvent.ACTION_DOWN))
        assertTrue(shouldResetInactivity(keepScreenOn = true, actionMasked = MotionEvent.ACTION_UP))
    }

    @Test
    fun `a cancelled touch does not re-arm the inactivity timer`() {
        assertFalse(shouldResetInactivity(keepScreenOn = true, actionMasked = MotionEvent.ACTION_CANCEL))
    }

    @Test
    fun `no touch re-arms the timer while the screen is not kept on`() {
        assertFalse(shouldResetInactivity(keepScreenOn = false, actionMasked = MotionEvent.ACTION_DOWN))
    }

    private fun mode(
        modeId: Int,
        width: Int = 1080,
        height: Int = 2400,
        hz: Float,
    ): DisplayModeInfo = DisplayModeInfo(modeId = modeId, width = width, height = height, refreshRate = hz)

    private companion object {
        const val KNOWN_PAD = 7
        const val STRANGER = 8
    }
}
