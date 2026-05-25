// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalGamepadRegistryTest {
    @Test
    fun `device with SOURCE_GAMEPAD bit is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `device with SOURCE_JOYSTICK bit is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `device with the exact mask seen in the Generic USB Joystick bug is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = 0x1000111,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `device with both SOURCE_GAMEPAD and SOURCE_JOYSTICK is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `pure keyboard with no gamepad-source bits is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_KEYBOARD,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `touchscreen device is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_TOUCHSCREEN,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `mouse device is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_MOUSE,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `dpad-only device without GAMEPAD or JOYSTICK is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_DPAD,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `zero source mask is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = 0,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `alphabetic keyboard with SOURCE_JOYSTICK is still rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `KEYBOARD_TYPE_NONE with SOURCE_JOYSTICK is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `partial source bits without a full GAMEPAD class are rejected`() {
        val incomplete = InputDevice.SOURCE_GAMEPAD and 0x400.inv()
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = incomplete,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }
}
