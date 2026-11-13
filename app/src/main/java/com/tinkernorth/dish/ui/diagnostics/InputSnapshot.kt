// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SEPARATOR = " · "

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
    val extButtons: Int = 0,
    val rightClick: Boolean = false,
    val wheel: Int = 0,
) {
    fun leftSample(): StickSample = StickSample(lx / AXIS_SCALE, ly / AXIS_SCALE)

    fun rightSample(): StickSample = StickSample(rx / AXIS_SCALE, ry / AXIS_SCALE)

    companion object {
        const val AXIS_SCALE = 32768f
    }
}

fun parseInputSnapshot(
    json: Json,
    raw: String,
): InputSnapshot? {
    if (raw.isEmpty()) return null
    return runCatching { json.decodeFromString(InputSnapshot.serializer(), raw) }.getOrNull()
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

// Protocol 4 extended-button bits (mirror of dish_wire::EXT_BUTTON_* in wire_encoders.h): what the
// snapshot's `extButtons` word means.
enum class ExtendedButton(
    val bit: Int,
    @StringRes val label: Int,
) {
    RIGHT_PADDLE1(0x0001, R.string.inspector_ext_right_paddle_1),
    LEFT_PADDLE1(0x0002, R.string.inspector_ext_left_paddle_1),
    RIGHT_PADDLE2(0x0004, R.string.inspector_ext_right_paddle_2),
    LEFT_PADDLE2(0x0008, R.string.inspector_ext_left_paddle_2),
    MISC1(0x0010, R.string.inspector_ext_misc_1),
    LEFT_PAD_CLICK(0x0020, R.string.inspector_ext_left_pad_click),
    RIGHT_PAD_CLICK(0x0040, R.string.inspector_ext_right_pad_click),
    LEFT_TRIGGER_CLICK(0x0080, R.string.inspector_ext_left_trigger_click),
    RIGHT_TRIGGER_CLICK(0x0100, R.string.inspector_ext_right_trigger_click),
    LEFT_STICK_TOUCH(0x0200, R.string.inspector_ext_left_stick_touch),
    RIGHT_STICK_TOUCH(0x0400, R.string.inspector_ext_right_stick_touch),
    LEFT_GRIP_TOUCH(0x0800, R.string.inspector_ext_left_grip_touch),
    RIGHT_GRIP_TOUCH(0x1000, R.string.inspector_ext_right_grip_touch),
}

internal fun Context.pressedText(snapshot: InputSnapshot): String {
    val wire = WireButton.entries.filter { (snapshot.buttons and it.bit) != 0 }.map { it.label }
    val extended = ExtendedButton.entries.filter { (snapshot.extButtons and it.bit) != 0 }.map { getString(it.label) }
    val pressed = (wire + extended).joinToString(SEPARATOR)
    return getString(R.string.inspector_pressed, pressed.ifEmpty { getString(R.string.inspector_none) })
}

internal fun Context.touchHintText(snapshot: InputSnapshot): String {
    if (!snapshot.touchValid) return getString(R.string.inspector_touch_missing)
    val pointerExtras =
        buildList {
            if (snapshot.rightClick) add(getString(R.string.inspector_right_click))
            if (snapshot.wheel != 0) add(getString(R.string.inspector_wheel, snapshot.wheel))
        }
    return pointerExtras.joinToString(SEPARATOR)
}
