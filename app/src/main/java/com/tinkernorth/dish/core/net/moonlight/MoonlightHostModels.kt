// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import kotlinx.serialization.Serializable

/**
 * A Moonlight-compatible host (Sunshine / Apollo / Vibepollo / Wolf) the dish
 * can pair with and stream input to. Discovered over mDNS (`_nvstream._tcp`) or
 * entered manually.
 */
@Serializable
data class MoonlightHost(
    val name: String,
    val address: String,
    // 47989 (HTTP) and 47984 (HTTPS) are the documented defaults; both are read
    // from /serverinfo when known and never assumed elsewhere.
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val httpsPort: Int = DEFAULT_HTTPS_PORT,
    // Stable identity from /serverinfo uniqueid; empty until first probed.
    val uniqueId: String = "",
    val manual: Boolean = false,
) {
    val id: String get() = idFor(address, uniqueId)

    companion object {
        const val DEFAULT_HTTP_PORT = 47989
        const val DEFAULT_HTTPS_PORT = 47984
        const val ID_PREFIX = "moonlight:"

        // Prefer the stable uniqueid so a host that changes IP keeps one identity;
        // fall back to the address for a host not yet probed.
        fun idFor(
            address: String,
            uniqueId: String,
        ): String = if (uniqueId.isNotBlank()) "${ID_PREFIX}uid:$uniqueId" else "$ID_PREFIX$address"
    }
}

@Serializable
data class RememberedMoonlight(
    val id: String,
    val name: String,
    val address: String,
    val httpPort: Int = MoonlightHost.DEFAULT_HTTP_PORT,
    val httpsPort: Int = MoonlightHost.DEFAULT_HTTPS_PORT,
    val uniqueId: String = "",
    // The app id and title the session creator settled on. Per host, not per
    // binding: every controller on this host shares the one session.
    val lastAppId: String = "",
    val lastAppName: String = "",
    // The emulated-device pick (CONTROLLER_ARRIVAL type): Auto/Xbox/PS/Nintendo.
    val emulatedType: Int = MoonlightEmulatedType.AUTO,
    // Whether the host has ever accepted this device, as opposed to one the user has
    // only shown durable interest in (added by address, or bound to). Both belong in
    // this list; only the first is trust. Defaults true because every record written
    // before this field existed was written by a completed pairing.
    val paired: Boolean = true,
) {
    fun toHost(): MoonlightHost =
        MoonlightHost(
            name = name,
            address = address,
            httpPort = httpPort,
            httpsPort = httpsPort,
            uniqueId = uniqueId,
        )
}

/**
 * The user-facing emulated-device picker mapped onto CONTROLLER_ARRIVAL types.
 * AUTO is a client convenience (Wolf control.hpp uses 0xFF): the session resolves
 * it to the type that best matches the local controller before it hits the wire.
 * It is 0xFF and never 0, because 0 is CONTROLLER_TYPE_UNKNOWN on the wire and
 * the satellite's own CONTROLLER_TYPE_XBOX as well, so a stored 0 is ambiguous
 * twice over; [fromStored] migrates one back to Auto on read.
 */
object MoonlightEmulatedType {
    const val AUTO = 0xFF
    const val XBOX = MoonlightControlProtocol.CONTROLLER_TYPE_XBOX
    const val PLAYSTATION = MoonlightControlProtocol.CONTROLLER_TYPE_PS
    const val NINTENDO = MoonlightControlProtocol.CONTROLLER_TYPE_NINTENDO

    val ORDER = listOf(AUTO, XBOX, PLAYSTATION, NINTENDO)

    fun fromStored(stored: Int): Int = if (stored == MoonlightControlProtocol.CONTROLLER_TYPE_UNKNOWN) AUTO else stored

    fun resolve(
        picked: Int,
        sourceHasMotion: Boolean,
    ): Int =
        when {
            picked != AUTO -> picked
            sourceHasMotion -> PLAYSTATION
            else -> XBOX
        }

    fun typeMaximum(type: Int): Int = if (type == PLAYSTATION) PLAYSTATION_MAXIMUM else BASE_MAXIMUM

    fun capabilityBits(
        type: Int,
        sourceBits: Int,
    ): Int = typeMaximum(type) and sourceBits

    fun supportedButtons(capabilities: Int): Int =
        if (capabilities and MoonlightControlProtocol.CAP_TOUCHPAD != 0) {
            BASE_BUTTONS or MoonlightControlProtocol.BTN_TOUCHPAD
        } else {
            BASE_BUTTONS
        }

    // Trigger rumble and battery describe the PHYSICAL pad's surfaces, not the
    // emulated identity, so every type may carry them (moonlight-qt advertises the
    // same way); the source bits decide whether they actually ride.
    private const val BASE_MAXIMUM =
        MoonlightControlProtocol.CAP_ANALOG_TRIGGERS or MoonlightControlProtocol.CAP_RUMBLE or
            MoonlightControlProtocol.CAP_TRIGGER_RUMBLE or MoonlightControlProtocol.CAP_BATTERY

    private const val PLAYSTATION_MAXIMUM = 0xFF

    private const val BASE_BUTTONS = 0xFFFF
}
