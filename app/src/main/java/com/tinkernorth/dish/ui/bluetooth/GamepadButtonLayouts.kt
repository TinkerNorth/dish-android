// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.bluetooth

/**
 * Pure translators between the two button-bit dialects used in the app:
 *
 *   - **XUSB** (`wButtons`): the XInput-compatible layout produced by the
 *     physical-controller path ([com.tinkernorth.dish.ui.main.GamepadInputProcessor])
 *     and consumed by the Wi-Fi path (see `XUSB_REPORT` in satellite_jni.cpp).
 *   - **HID**: the 14-button + 4-bit hat-switch layout used by the on-screen
 *     gamepad ([com.tinkernorth.dish.ui.common.GamepadTouchView]) and the
 *     Bluetooth HID descriptor ([buildHidDescriptor] / [buildHidReport]).
 *
 * Each producer emits bits in its native dialect. The matching consumer is
 * identity; the off-diagonal consumers must translate. Keep these helpers
 * pure (no Android, no state) so they stay JVM-unit-testable.
 *
 * The block below is the XUSB `wButtons` bitfield as defined by XInput.
 */
private const val XUSB_DPAD_UP = 0x0001
private const val XUSB_DPAD_DOWN = 0x0002
private const val XUSB_DPAD_LEFT = 0x0004
private const val XUSB_DPAD_RIGHT = 0x0008
private const val XUSB_START = 0x0010
private const val XUSB_BACK = 0x0020
private const val XUSB_LEFT_THUMB = 0x0040
private const val XUSB_RIGHT_THUMB = 0x0080
private const val XUSB_LEFT_SHOULDER = 0x0100
private const val XUSB_RIGHT_SHOULDER = 0x0200
private const val XUSB_GUIDE = 0x0400
private const val XUSB_A = 0x1000
private const val XUSB_B = 0x2000
private const val XUSB_X = 0x4000
private const val XUSB_Y = 0x8000

private const val XUSB_DPAD_MASK = 0x000F

// ── HID button bits (matches GamepadTouchView.BTN_*) ─────────────────────
private const val HID_A = 0x0001
private const val HID_B = 0x0002
private const val HID_X = 0x0004
private const val HID_Y = 0x0008
private const val HID_LB = 0x0010
private const val HID_RB = 0x0020
private const val HID_SELECT = 0x0040
private const val HID_START = 0x0080
private const val HID_LS = 0x0100
private const val HID_RS = 0x0200
private const val HID_HOME = 0x0400

// ── HID hat-switch codes (matches GamepadTouchView.HAT_*) ────────────────
private const val HAT_NEUTRAL = 0
private const val HAT_N = 1
private const val HAT_NE = 2
private const val HAT_E = 3
private const val HAT_SE = 4
private const val HAT_S = 5
private const val HAT_SW = 6
private const val HAT_W = 7
private const val HAT_NW = 8

/**
 * Translate an XUSB `wButtons` value (as produced by `GamepadInputProcessor`)
 * into the `(hidButtons, hatSwitch)` pair expected by [buildHidReport].
 *
 * The low-nibble XUSB d-pad bits are folded into the hat; the remaining XUSB
 * button bits are remapped to their HID positions. Unknown bits are dropped.
 */
fun xusbToHid(wButtons: Int): Pair<Int, Int> {
    val hat = dpadBitsToHat(wButtons and XUSB_DPAD_MASK)
    var hid = 0
    if (wButtons and XUSB_START != 0) hid = hid or HID_START
    if (wButtons and XUSB_BACK != 0) hid = hid or HID_SELECT
    if (wButtons and XUSB_LEFT_THUMB != 0) hid = hid or HID_LS
    if (wButtons and XUSB_RIGHT_THUMB != 0) hid = hid or HID_RS
    if (wButtons and XUSB_LEFT_SHOULDER != 0) hid = hid or HID_LB
    if (wButtons and XUSB_RIGHT_SHOULDER != 0) hid = hid or HID_RB
    if (wButtons and XUSB_GUIDE != 0) hid = hid or HID_HOME
    if (wButtons and XUSB_A != 0) hid = hid or HID_A
    if (wButtons and XUSB_B != 0) hid = hid or HID_B
    if (wButtons and XUSB_X != 0) hid = hid or HID_X
    if (wButtons and XUSB_Y != 0) hid = hid or HID_Y
    return hid to hat
}

/**
 * Inverse of [xusbToHid]: translate the HID `(buttons, hat)` pair emitted by
 * `GamepadTouchView` into an XUSB `wButtons` value the Wi-Fi path can hand
 * verbatim to `SatelliteNative.sendReport`.
 */
fun hidToXusb(
    hidButtons: Int,
    hat: Int,
): Int {
    var w = hatToDpadBits(hat)
    if (hidButtons and HID_START != 0) w = w or XUSB_START
    if (hidButtons and HID_SELECT != 0) w = w or XUSB_BACK
    if (hidButtons and HID_LS != 0) w = w or XUSB_LEFT_THUMB
    if (hidButtons and HID_RS != 0) w = w or XUSB_RIGHT_THUMB
    if (hidButtons and HID_LB != 0) w = w or XUSB_LEFT_SHOULDER
    if (hidButtons and HID_RB != 0) w = w or XUSB_RIGHT_SHOULDER
    if (hidButtons and HID_HOME != 0) w = w or XUSB_GUIDE
    if (hidButtons and HID_A != 0) w = w or XUSB_A
    if (hidButtons and HID_B != 0) w = w or XUSB_B
    if (hidButtons and HID_X != 0) w = w or XUSB_X
    if (hidButtons and HID_Y != 0) w = w or XUSB_Y
    return w
}

private fun dpadBitsToHat(dpadBits: Int): Int {
    val up = dpadBits and XUSB_DPAD_UP != 0
    val down = dpadBits and XUSB_DPAD_DOWN != 0
    val left = dpadBits and XUSB_DPAD_LEFT != 0
    val right = dpadBits and XUSB_DPAD_RIGHT != 0
    return when {
        up && right -> HAT_NE
        right && down -> HAT_SE
        down && left -> HAT_SW
        left && up -> HAT_NW
        up -> HAT_N
        right -> HAT_E
        down -> HAT_S
        left -> HAT_W
        else -> HAT_NEUTRAL
    }
}

private fun hatToDpadBits(hat: Int): Int =
    when (hat) {
        HAT_N -> XUSB_DPAD_UP
        HAT_NE -> XUSB_DPAD_UP or XUSB_DPAD_RIGHT
        HAT_E -> XUSB_DPAD_RIGHT
        HAT_SE -> XUSB_DPAD_RIGHT or XUSB_DPAD_DOWN
        HAT_S -> XUSB_DPAD_DOWN
        HAT_SW -> XUSB_DPAD_DOWN or XUSB_DPAD_LEFT
        HAT_W -> XUSB_DPAD_LEFT
        HAT_NW -> XUSB_DPAD_LEFT or XUSB_DPAD_UP
        else -> 0
    }
