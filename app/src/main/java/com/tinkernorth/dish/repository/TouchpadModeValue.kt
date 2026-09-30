// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

// Wire values match `touchpadModeName()` in `satellite/src/core/types.h`; kept as strings to round-trip without a mapping shim.
const val TOUCHPAD_MODE_OFF = "off"
const val TOUCHPAD_MODE_DS4 = "ds4"
const val TOUCHPAD_MODE_MOUSE = "mouse"

val TOUCHPAD_MODES: List<String> = listOf(TOUCHPAD_MODE_OFF, TOUCHPAD_MODE_DS4, TOUCHPAD_MODE_MOUSE)

fun isValidTouchpadMode(s: String?): Boolean = s != null && s in TOUCHPAD_MODES
