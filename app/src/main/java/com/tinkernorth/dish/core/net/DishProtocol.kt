// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

// The dish<->satellite protocol versions this client speaks. A satellite accepts its own
// version range and 409s anything outside it with a `supported` echo (docs/contract.md
// §Versioning; released 1.x satellites accept exactly version 1), so the client offers the
// best it can, downgrades once on the echo inside [MIN, CURRENT], and beyond that range
// tells the user which side to update. An in-range but older pairing still works fully;
// both ends surface a soft "update for the newest features" hint.
object DishProtocol {
    const val MIN = 1
    const val CURRENT = 2

    // v2 replaced the appended touchpad fields with the pointer frame that carries the
    // mouse buttons and the wheel, so extended mouse is exactly "the satellite is v2+".
    const val EXTENDED_MOUSE = 2

    enum class Compat {
        UNKNOWN,
        CURRENT,
        SATELLITE_UPDATE_AVAILABLE,
        SATELLITE_UPDATE_REQUIRED,
        APP_UPDATE_REQUIRED,
    }

    fun compatFor(advertised: Int?): Compat =
        when {
            advertised == null || advertised <= 0 -> Compat.UNKNOWN
            advertised < MIN -> Compat.SATELLITE_UPDATE_REQUIRED
            advertised > CURRENT -> Compat.APP_UPDATE_REQUIRED
            advertised < CURRENT -> Compat.SATELLITE_UPDATE_AVAILABLE
            else -> Compat.CURRENT
        }

    // The version to offer a satellite whose advertisement is [advertised]; null when no
    // shared version exists. An unknown satellite gets CURRENT optimistically, and the
    // 409's `supported` echo settles the real answer in one round trip.
    fun speakFor(advertised: Int?): Int? =
        when {
            advertised == null || advertised <= 0 -> CURRENT
            advertised < MIN -> null
            else -> minOf(advertised, CURRENT)
        }
}
