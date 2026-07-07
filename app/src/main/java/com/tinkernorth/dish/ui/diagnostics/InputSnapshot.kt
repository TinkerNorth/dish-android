// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One frame of a device's wire-facing input state, mirroring the native
 * `formatDeviceStateJson` layout. Every field defaults so an older or partial
 * snapshot still parses.
 */
@Serializable
data class InputSnapshot(
    val buttons: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val motionValid: Boolean = false,
    val gx: Int = 0,
    val gy: Int = 0,
    val gz: Int = 0,
    val ax: Int = 0,
    val ay: Int = 0,
    val az: Int = 0,
    val touchValid: Boolean = false,
    val f0Active: Boolean = false,
    val f0Id: Int = 0,
    val f0X: Int = 0,
    val f0Y: Int = 0,
    val f1Active: Boolean = false,
    val f1Id: Int = 0,
    val f1X: Int = 0,
    val f1Y: Int = 0,
    val click: Boolean = false,
) {
    fun leftSample(): StickSample = StickSample(lx / AXIS_SCALE, ly / AXIS_SCALE)

    fun rightSample(): StickSample = StickSample(rx / AXIS_SCALE, ry / AXIS_SCALE)

    companion object {
        const val AXIS_SCALE = 32768f

        fun parse(
            json: Json,
            raw: String,
        ): InputSnapshot? =
            if (raw.isEmpty()) {
                null
            } else {
                runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
            }
    }
}

// Wire XUSB button bits (mirror of gamepad_input.h): what the snapshot's `buttons` mask means.
enum class WireButton(
    val bit: Int,
    val label: String,
) {
    DPAD_UP(0x0001, "▲"),
    DPAD_DOWN(0x0002, "▼"),
    DPAD_LEFT(0x0004, "◀"),
    DPAD_RIGHT(0x0008, "▶"),
    START(0x0010, "Start"),
    BACK(0x0020, "Back"),
    THUMB_L(0x0040, "LS"),
    THUMB_R(0x0080, "RS"),
    LB(0x0100, "LB"),
    RB(0x0200, "RB"),
    GUIDE(0x0400, "Guide"),
    A(0x1000, "A"),
    B(0x2000, "B"),
    X(0x4000, "X"),
    Y(0x8000, "Y"),
}
