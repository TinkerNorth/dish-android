// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.bluetooth

/**
 * Pure-Kotlin helpers for the Bluetooth HID gamepad profile.
 *
 * The stateful pieces that used to live here (profile-proxy binding, HID app
 * registration, host connect/disconnect) moved to [BluetoothHidSession] /
 * [AndroidHidProxyClient] so the FSM can be unit tested without the Android
 * framework. What remains is the immutable wire contract: the report
 * descriptor, the [GamepadProfile] enum and [buildHidReport].
 */
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

// ── HID Report Descriptor ────────────────────────────────────────────────
// Standard HID gamepad: 2 sticks (16-bit X/Y), 2 triggers (8-bit),
// 14 buttons, 1 hat switch (d-pad). Works as Xbox-compatible on all hosts.

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
        0x01, //   Report ID (1)
        // ── 14 Buttons ──
        0x05,
        0x09, //   Usage Page (Buttons)
        0x19,
        0x01, //   Usage Minimum (1)
        0x29,
        0x0E, //   Usage Maximum (14)
        0x15,
        0x00, //   Logical Minimum (0)
        0x25,
        0x01, //   Logical Maximum (1)
        0x75,
        0x01, //   Report Size (1)
        0x95.toByte(),
        0x0E, //   Report Count (14)
        0x81.toByte(),
        0x02, //   Input (Variable)
        // 2-bit padding to align to byte
        0x75,
        0x01,
        0x95.toByte(),
        0x02,
        0x81.toByte(),
        0x03, //   Input (Constant)
        // ── Hat Switch (D-Pad) ──
        0x05,
        0x01,
        0x09,
        0x39, //   Usage (Hat Switch)
        0x15,
        0x01, //   Logical Minimum (1)
        0x25,
        0x08, //   Logical Maximum (8)
        0x35,
        0x00, //   Physical Minimum (0)
        0x46,
        0x3B,
        0x01, //   Physical Maximum (315)
        0x65,
        0x14, //   Unit (Degrees)
        0x75,
        0x04, //   Report Size (4)
        0x95.toByte(),
        0x01, //   Report Count (1)
        0x81.toByte(),
        0x42, //   Input (Variable, Null State)
        // 4-bit padding
        0x75,
        0x04,
        0x95.toByte(),
        0x01,
        0x81.toByte(),
        0x03,
        // ── Reset globals leaked by Hat Switch ──
        0x35,
        0x00,
        0x45,
        0x00,
        0x65,
        0x00,
        // ── Left Stick X/Y (16-bit signed) ──
        0x05,
        0x01,
        0x09,
        0x30, //   Usage (X)
        0x09,
        0x31, //   Usage (Y)
        0x16,
        0x00,
        0x80.toByte(), //   Logical Minimum (-32768)
        0x26,
        0xFF.toByte(),
        0x7F, //   Logical Maximum (32767)
        0x75,
        0x10, //   Report Size (16)
        0x95.toByte(),
        0x02, //   Report Count (2)
        0x81.toByte(),
        0x02,
        // ── Right Stick X/Y (16-bit signed) ──
        0x09,
        0x33, //   Usage (Rx)
        0x09,
        0x34, //   Usage (Ry)
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
        // ── Triggers (8-bit unsigned) ──
        0x05,
        0x02, //   Usage Page (Simulation)
        0x09,
        0xC5.toByte(), //   Usage (Brake / Left Trigger)
        0x09,
        0xC4.toByte(), //   Usage (Accelerator / Right Trigger)
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

/**
 * Packs a single HID gamepad input report into a 14-byte little-endian frame.
 *
 * Wire layout (all little-endian; byte indices are zero-based):
 *
 *   [0]      report id (always [REPORT_ID] = 1)
 *   [1..2]   buttons — u16 bitfield (HID layout; see `xusbToHid` for the
 *            mapping from the native input thread's XUSB `wButtons`)
 *   [3]      hat switch — u8, low nibble: 0=neutral, 1=N, 2=NE, 3=E, 4=SE,
 *            5=S, 6=SW, 7=W, 8=NW
 *   [4..5]   left stick X  — i16, +32767 = right,  -32767 = left
 *   [6..7]   left stick Y  — i16, +32767 = up,     -32767 = down (Xbox/XInput)
 *   [8..9]   right stick X — i16, +32767 = right,  -32767 = left
 *   [10..11] right stick Y — i16, +32767 = up,     -32767 = down (Xbox/XInput)
 *   [12]     left trigger  — u8, 0..255
 *   [13]     right trigger — u8, 0..255
 *
 * Callers are responsible for delivering axis values in the Xbox/XInput
 * "stick up = positive Y" convention; this function performs no sign
 * inversion. See `processNativeMotionEvent` in `satellite_jni.cpp`
 * (physical path) and `computeStickAxes` (virtual path) for the producers.
 *
 * Button and trigger ints are masked to their on-wire width; hat to low
 * nibble. Axis shorts are stored as-is.
 */
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
