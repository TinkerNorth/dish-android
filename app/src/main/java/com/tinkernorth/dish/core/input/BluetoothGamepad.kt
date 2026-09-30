// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

// The HID report descriptor, one item per line as the HID 1.11 spec writes them: a tag byte
// followed by its data bytes. Kept as text so the structure reads the way a descriptor tool
// prints it; hidItems turns it into the bytes the SDP record carries.
internal fun buildHidDescriptor(): ByteArray =
    hidItems(
        "05 01", // Usage Page (Generic Desktop)
        "09 05", // Usage (Gamepad)
        "A1 01", // Collection (Application)
        "85 01", // Report ID (1)
        "05 09", // Usage Page (Buttons)
        "19 01", // Usage Minimum (1)
        "29 0E", // Usage Maximum (14)
        "15 00", // Logical Minimum (0)
        "25 01", // Logical Maximum (1)
        "75 01", // Report Size (1)
        "95 0E", // Report Count (14)
        "81 02", // Input (Variable)
        "75 01 95 02 81 03", // 2-bit padding to byte boundary
        "05 01", // Usage Page (Generic Desktop)
        "09 39", // Usage (Hat Switch)
        "15 01", // Logical Minimum (1)
        "25 08", // Logical Maximum (8)
        "35 00", // Physical Minimum (0)
        "46 3B 01", // Physical Maximum (315)
        "65 14", // Unit (Degrees)
        "75 04", // Report Size (4)
        "95 01", // Report Count (1)
        "81 42", // Input (Variable, Null State)
        "75 04 95 01 81 03", // 4-bit padding to byte boundary
        "35 00 45 00 65 00", // Reset globals leaked by Hat Switch
        "05 01", // Usage Page (Generic Desktop)
        "09 30", // Usage (X)
        "09 31", // Usage (Y)
        "16 00 80", // Logical Minimum (-32768)
        "26 FF 7F", // Logical Maximum (32767)
        "75 10", // Report Size (16)
        "95 02", // Report Count (2)
        "81 02", // Input (Variable)
        "09 33", // Usage (Rx)
        "09 34", // Usage (Ry)
        "16 00 80", // Logical Minimum (-32768)
        "26 FF 7F", // Logical Maximum (32767)
        "75 10", // Report Size (16)
        "95 02", // Report Count (2)
        "81 02", // Input (Variable)
        "05 02", // Usage Page (Simulation)
        "09 C5", // Usage (Brake / Left Trigger)
        "09 C4", // Usage (Accelerator / Right Trigger)
        "15 00", // Logical Minimum (0)
        "26 FF 00", // Logical Maximum (255)
        "75 08", // Report Size (8)
        "95 02", // Report Count (2)
        "81 02", // Input (Variable)
        "C0", // End Collection
    )

// Each item is space-separated hex bytes; the descriptor is their concatenation.
private fun hidItems(vararg items: String): ByteArray =
    items
        .flatMap { item -> item.split(' ').map { it.toInt(HEX_RADIX).toByte() } }
        .toByteArray()

private const val HEX_RADIX = 16

internal const val REPORT_ID = 1
internal const val REPORT_SIZE = 14

// Byte offsets of the input report the descriptor above declares: report id, buttons u16, hat u8,
// four int16 axes, two u8 triggers; every multi-byte field little-endian.
private const val REPORT_OFF_ID = 0
private const val REPORT_OFF_BUTTONS_LO = 1
private const val REPORT_OFF_BUTTONS_HI = 2
private const val REPORT_OFF_HAT = 3
private const val REPORT_OFF_LX_LO = 4
private const val REPORT_OFF_LX_HI = 5
private const val REPORT_OFF_LY_LO = 6
private const val REPORT_OFF_LY_HI = 7
private const val REPORT_OFF_RX_LO = 8
private const val REPORT_OFF_RX_HI = 9
private const val REPORT_OFF_RY_LO = 10
private const val REPORT_OFF_RY_HI = 11
private const val REPORT_OFF_LT = 12
private const val REPORT_OFF_RT = 13
private const val BYTE_MASK = 0xFF

// Caller passes XInput axes (stick-up = +Y); HID Generic Desktop Y is the opposite sign, so Y is negated here.
internal fun buildHidReport(
    buttons: Int,
    hatSwitch: Int,
    leftX: Short,
    leftY: Short,
    rightX: Short,
    rightY: Short,
    leftTrigger: Int,
    rightTrigger: Int,
): ByteArray {
    val hidLeftY = (-leftY.toInt()).coerceAtMost(Short.MAX_VALUE.toInt())
    val hidRightY = (-rightY.toInt()).coerceAtMost(Short.MAX_VALUE.toInt())
    val report = ByteArray(REPORT_SIZE)
    report[REPORT_OFF_ID] = REPORT_ID.toByte()
    report[REPORT_OFF_BUTTONS_LO] = lowByte(buttons)
    report[REPORT_OFF_BUTTONS_HI] = highByte(buttons)
    report[REPORT_OFF_HAT] = lowByte(hatSwitch)
    report[REPORT_OFF_LX_LO] = lowByte(leftX.toInt())
    report[REPORT_OFF_LX_HI] = highByte(leftX.toInt())
    report[REPORT_OFF_LY_LO] = lowByte(hidLeftY)
    report[REPORT_OFF_LY_HI] = highByte(hidLeftY)
    report[REPORT_OFF_RX_LO] = lowByte(rightX.toInt())
    report[REPORT_OFF_RX_HI] = highByte(rightX.toInt())
    report[REPORT_OFF_RY_LO] = lowByte(hidRightY)
    report[REPORT_OFF_RY_HI] = highByte(hidRightY)
    report[REPORT_OFF_LT] = lowByte(leftTrigger)
    report[REPORT_OFF_RT] = lowByte(rightTrigger)
    return report
}

private fun lowByte(value: Int): Byte = (value and BYTE_MASK).toByte()

private fun highByte(value: Int): Byte = ((value shr Byte.SIZE_BITS) and BYTE_MASK).toByte()
