// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

// The dish<->satellite protocol versions this client speaks. A satellite accepts its own
// version range and 409s anything outside it with a `supported` echo (docs/contract.md
// §Versioning; released 1.x satellites accept exactly version 1), so the client offers the
// best it can, downgrades once on the echo inside [MIN, CURRENT], and beyond that range
// tells the user which side to update. An in-range but older pairing still works fully;
// both ends surface a soft "update for the newest features" hint.
object DishProtocol {
    const val DISH_PROTOCOL_MIN = 1

    // 3 added the satellite's HAPTIC_AUDIO return path (0x0015, cap `hapticAudio`): the
    // DualSense's two actuator lanes as a stereo Opus stream, for a client that can play
    // the waveform into the pad's own audio function. This client advertises the cap for
    // a USB DualSense whose endpoint the platform opens at four channels (a second quad
    // AudioTrack on the same endpoint, see source/audio/SpeakerPlayoutPlan.kt); every
    // other slot (a phone-only virtual pad, a Bluetooth pad, a stereo-only endpoint) leaves
    // it off and the satellite reduces the lanes to RUMBLE 0x0009, which the rumble paths
    // already render. No frame shape changed between 2 and 3.
    const val DISH_PROTOCOL_CURRENT = 3

    // v2 replaced the appended touchpad fields with the pointer frame that carries the
    // mouse buttons and the wheel, so extended mouse is exactly "the satellite is v2+".
    const val DISH_PROTOCOL_EXTENDED_MOUSE = 2

    enum class DishProtocolCompat {
        UNKNOWN,
        CURRENT,
        SATELLITE_UPDATE_AVAILABLE,
        SATELLITE_UPDATE_REQUIRED,
        APP_UPDATE_REQUIRED,
    }

    fun dishProtocolCompatFor(advertised: Int?): DishProtocolCompat =
        when {
            advertised == null || advertised <= 0 -> DishProtocolCompat.UNKNOWN
            advertised < DISH_PROTOCOL_MIN -> DishProtocolCompat.SATELLITE_UPDATE_REQUIRED
            advertised > DISH_PROTOCOL_CURRENT -> DishProtocolCompat.APP_UPDATE_REQUIRED
            advertised < DISH_PROTOCOL_CURRENT -> DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE
            else -> DishProtocolCompat.CURRENT
        }

    // The version to offer a satellite whose advertisement is [advertised]; null when no
    // shared version exists. An unknown satellite gets the current version optimistically, and the
    // 409's `supported` echo settles the real answer in one round trip.
    fun dishProtocolSpeakFor(advertised: Int?): Int? =
        when {
            advertised == null || advertised <= 0 -> DISH_PROTOCOL_CURRENT
            advertised < DISH_PROTOCOL_MIN -> null
            else -> minOf(advertised, DISH_PROTOCOL_CURRENT)
        }
}
