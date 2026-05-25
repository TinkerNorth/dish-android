// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

object BluetoothGamepad {
    enum class GamepadProfile(
        val profileName: String,
        val sdpName: String,
        val sdpDescription: String,
        val sdpProvider: String,
    ) {
        XBOX(
            profileName = "Xbox",
            sdpName = "Dish Xbox Controller",
            sdpDescription = "Wireless Xbox Controller",
            sdpProvider = "TinkerNorth",
        ),
        PLAYSTATION(
            profileName = "PlayStation",
            sdpName = "Dish PS Controller",
            sdpDescription = "Wireless PlayStation Controller",
            sdpProvider = "TinkerNorth",
        ),
    }
}

@Suppress("MagicNumber", "LongMethod")
internal fun buildHidDescriptor(): ByteArray =
    byteArrayOf(
        0x05,
        0x01, // Usage Page (Generic Desktop)
        0x09,
        0x05, // Usage (Gamepad)
        0xA1.toByte(),
        0x01, // Collection (Application)
        0x85.toByte(),
        0x01, // Report ID (1)
        0x05,
        0x09, // Usage Page (Buttons)
        0x19,
        0x01, // Usage Minimum (1)
        0x29,
        0x0E, // Usage Maximum (14)
        0x15,
        0x00, // Logical Minimum (0)
        0x25,
        0x01, // Logical Maximum (1)
        0x75,
        0x01, // Report Size (1)
        0x95.toByte(),
        0x0E, // Report Count (14)
        0x81.toByte(),
        0x02, // Input (Variable)
        0x75,
        0x01,
        0x95.toByte(),
        0x02,
        0x81.toByte(),
        0x03, // 2-bit padding to byte boundary
        0x05,
        0x01,
        0x09,
        0x39, // Usage (Hat Switch)
        0x15,
        0x01, // Logical Minimum (1)
        0x25,
        0x08, // Logical Maximum (8)
        0x35,
        0x00, // Physical Minimum (0)
        0x46,
        0x3B,
        0x01, // Physical Maximum (315)
        0x65,
        0x14, // Unit (Degrees)
        0x75,
        0x04, // Report Size (4)
        0x95.toByte(),
        0x01, // Report Count (1)
        0x81.toByte(),
        0x42, // Input (Variable, Null State)
        0x75,
        0x04,
        0x95.toByte(),
        0x01,
        0x81.toByte(),
        0x03,
        0x35,
        0x00,
        0x45,
        0x00,
        0x65,
        0x00, // Reset globals leaked by Hat Switch
        0x05,
        0x01,
        0x09,
        0x30, // Usage (X)
        0x09,
        0x31, // Usage (Y)
        0x16,
        0x00,
        0x80.toByte(), // Logical Minimum (-32768)
        0x26,
        0xFF.toByte(),
        0x7F, // Logical Maximum (32767)
        0x75,
        0x10, // Report Size (16)
        0x95.toByte(),
        0x02, // Report Count (2)
        0x81.toByte(),
        0x02,
        0x09,
        0x33, // Usage (Rx)
        0x09,
        0x34, // Usage (Ry)
        0x16,
        0x00,
        0x80.toByte(),
        0x26,
        0xFF.toByte(),
        0x7F,
        0x75,
        0x10,
        0x95.toByte(),
        0x02,
        0x81.toByte(),
        0x02,
        0x05,
        0x02, // Usage Page (Simulation)
        0x09,
        0xC5.toByte(), // Usage (Brake / Left Trigger)
        0x09,
        0xC4.toByte(), // Usage (Accelerator / Right Trigger)
        0x15,
        0x00,
        0x26,
        0xFF.toByte(),
        0x00,
        0x75,
        0x08,
        0x95.toByte(),
        0x02,
        0x81.toByte(),
        0x02,
        0xC0.toByte(), // End Collection
    )

internal const val REPORT_ID = 1
internal const val REPORT_SIZE = 14

// Axis convention: Xbox/XInput, stick-up = positive Y. Caller does no sign inversion.
@Suppress("MagicNumber")
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
    val report = ByteArray(REPORT_SIZE)
    report[0] = REPORT_ID.toByte()
    report[1] = (buttons and 0xFF).toByte()
    report[2] = ((buttons shr 8) and 0xFF).toByte()
    report[3] = (hatSwitch and 0xFF).toByte()
    report[4] = (leftX.toInt() and 0xFF).toByte()
    report[5] = ((leftX.toInt() shr 8) and 0xFF).toByte()
    report[6] = (leftY.toInt() and 0xFF).toByte()
    report[7] = ((leftY.toInt() shr 8) and 0xFF).toByte()
    report[8] = (rightX.toInt() and 0xFF).toByte()
    report[9] = ((rightX.toInt() shr 8) and 0xFF).toByte()
    report[10] = (rightY.toInt() and 0xFF).toByte()
    report[11] = ((rightY.toInt() shr 8) and 0xFF).toByte()
    report[12] = (leftTrigger and 0xFF).toByte()
    report[13] = (rightTrigger and 0xFF).toByte()
    return report
}
