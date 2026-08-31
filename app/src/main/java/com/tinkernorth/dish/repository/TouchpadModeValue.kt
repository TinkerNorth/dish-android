// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

// Wire values match `touchpadModeName()` in `satellite/src/core/types.h`; kept as strings to round-trip without a mapping shim.
object TouchpadModeValue {
    const val OFF = "off"
    const val DS4 = "ds4"
    const val MOUSE = "mouse"

    val ALL: List<String> = listOf(OFF, DS4, MOUSE)

    fun isValid(s: String?): Boolean = s != null && s in ALL
}
