// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

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

private const val HAT_NEUTRAL = 0
private const val HAT_N = 1
private const val HAT_NE = 2
private const val HAT_E = 3
private const val HAT_SE = 4
private const val HAT_S = 5
private const val HAT_SW = 6
private const val HAT_W = 7
private const val HAT_NW = 8

// Packed Int (not Pair) to avoid per-report allocation on the ≤250 Hz hotpath.
// Layout: bits 0..15 = HID buttons, bits 16..19 = HID hat code.
fun xusbToHid(wButtons: Int): Int {
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
    return (hat shl 16) or (hid and 0xFFFF)
}

fun hidButtonsOf(packed: Int): Int = packed and 0xFFFF

fun hidHatOf(packed: Int): Int = (packed shr 16) and 0xF

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
