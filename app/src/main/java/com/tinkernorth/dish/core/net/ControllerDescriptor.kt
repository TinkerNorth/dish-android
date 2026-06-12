// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

/**
 * Builds the `controllers` array of the session PUT (and the single-descriptor
 * body of the controller PUT). A descriptor is always sent WHOLE: a toggle is
 * a re-send with one field changed; the satellite converges
 * (docs/contract.md §Controller).
 */
data class ControllerDescriptor(
    val ctrlIdx: Int,
    val type: Int,
    val caps: Int,
    val touchpadMode: String,
) {
    val wantsMouseControl: Boolean get() = touchpadMode == TOUCHPAD_MODE_MOUSE

    fun toJson(): String =
        buildString {
            append("{\"ctrlIdx\":").append(ctrlIdx)
            append(",\"type\":").append(type)
            append(",\"caps\":{")
            append("\"rumble\":").append((caps and CAP_RUMBLE) != 0)
            append(",\"motion\":").append((caps and CAP_MOTION) != 0)
            append(",\"analogTriggers\":").append((caps and CAP_ANALOG_TRIGGERS) != 0)
            append(",\"lightbar\":").append((caps and CAP_LIGHTBAR) != 0)
            append("}")
            append(",\"touchpadMode\":\"").append(sanitizedMode()).append("\"}")
        }

    private fun sanitizedMode(): String =
        when (touchpadMode) {
            TOUCHPAD_MODE_DS4, TOUCHPAD_MODE_MOUSE, TOUCHPAD_MODE_OFF -> touchpadMode
            else -> TOUCHPAD_MODE_OFF
        }

    companion object {
        // Mirror of satellite core/types.h CAP_*: wire constants.
        const val CAP_ANALOG_TRIGGERS = 0x0001
        const val CAP_RUMBLE = 0x0002
        const val CAP_MOTION = 0x0004
        const val CAP_LIGHTBAR = 0x0008

        // Protocol constants (never localized): valid descriptor touchpadMode values.
        const val TOUCHPAD_MODE_DS4 = "ds4"
        const val TOUCHPAD_MODE_MOUSE = "mouse"
        const val TOUCHPAD_MODE_OFF = "off"

        fun arrayJson(descriptors: List<ControllerDescriptor>): String =
            descriptors.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJson() }
    }
}
