// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

// A Steam Controller slot's trackpad pick. `wire` is what the store keeps; the ordinal is what
// the native router is told (gamepad::TrackpadPick, in this order).
enum class TrackpadPick(
    val wire: String,
) {
    STICK("stick"),
    PAD("pad"),
    MOUSE("mouse"),
    OFF("off"),
    ;

    companion object {
        fun fromWire(wire: String?): TrackpadPick? = entries.firstOrNull { it.wire == wire }
    }
}
