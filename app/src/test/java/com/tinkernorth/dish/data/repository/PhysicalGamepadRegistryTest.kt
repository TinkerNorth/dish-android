// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.repository

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isGamepadDeviceFromCapabilities].
 *
 * Pinned behaviour: the "Generic USB Joystick that doesn't ship an Android
 * key-layout file" case (sources=0x1000111, kbType=NON_ALPHABETIC) must be
 * accepted — the previous version of this classifier also required at least
 * one of A/B/X/Y/Start/Select to be advertised via `InputDevice.hasKeys`,
 * which rejected every cheap third-party pad.
 *
 * Tests use the raw capability bits instead of an [InputDevice] mock. The
 * Android source constants are compile-time `int` so they work in JVM
 * tests; mocking [InputDevice] itself crashes the test-worker JVM because
 * it's a final class with many native methods.
 *
 * The InputManager-driven add/remove/changed callbacks (and the deadzone
 * push) require a live Context + InputManager and aren't covered by these
 * JVM tests — they're exercised on-device through the slot list UI.
 */
class PhysicalGamepadRegistryTest {
    // ── Source-bit acceptance ───────────────────────────────────────────────

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
        // sources=0x1000111 = SOURCE_JOYSTICK | SOURCE_KEYBOARD | bits.
        // This is the device that was rejected before the hasKeys-gate was
        // dropped from isGamepad.
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

    // ── Source-bit rejection ────────────────────────────────────────────────

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
        // SOURCE_DPAD on its own isn't a gamepad — some IR remotes etc.
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

    // ── Keyboard-type gate ──────────────────────────────────────────────────

    @Test
    fun `alphabetic keyboard with SOURCE_JOYSTICK is still rejected`() {
        // Some Bluetooth combo devices expose a full querty keyboard *plus*
        // a joystick. KEYBOARD_TYPE_ALPHABETIC short-circuits before the
        // source check so we don't route alphabetic input through the
        // gamepad pipeline.
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
        // SOURCE_GAMEPAD = 0x401 (CLASS_BUTTON|0x400). A device that has only
        // the 0x400 bit but not CLASS_BUTTON wouldn't satisfy the strict-mask
        // check; verifies we use `== SOURCE_GAMEPAD` rather than `& 0x400 != 0`.
        val incomplete = InputDevice.SOURCE_GAMEPAD and 0x400.inv() // strip CLASS_BUTTON bit
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = incomplete,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }
}
