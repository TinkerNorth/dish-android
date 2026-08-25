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
    // The app id last launched on this host, remembered for one-tap reconnect.
    val lastAppId: String = "",
    // The emulated-device pick (CONTROLLER_ARRIVAL type): Auto/Xbox/PS/Nintendo.
    val emulatedType: Int = MoonlightEmulatedType.AUTO,
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
 */
object MoonlightEmulatedType {
    const val AUTO = 0xFF
    const val XBOX = MoonlightControlProtocol.CONTROLLER_TYPE_XBOX
    const val PLAYSTATION = MoonlightControlProtocol.CONTROLLER_TYPE_PS
    const val NINTENDO = MoonlightControlProtocol.CONTROLLER_TYPE_NINTENDO

    /** Resolve AUTO to a concrete arrival type; a real pick passes straight through. */
    fun resolve(picked: Int): Int = if (picked == AUTO) XBOX else picked
}
