// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.core.net.isIpv6Literal

private const val MIN_PORT = 1
private const val MAX_PORT = 65535

internal data class TypedSatellite(
    val host: String,
    val httpsPort: Int,
    val udpPort: Int,
)

// Every field is judged in the same pass rather than stopping at the first, so one attempt
// marks everything still to fix.
internal sealed interface TypedSatelliteResult {
    data class Accepted(
        val typed: TypedSatellite,
    ) : TypedSatelliteResult

    data class Rejected(
        val hostMissing: Boolean,
        val httpsPortInvalid: Boolean,
        val udpPortInvalid: Boolean,
        val hostIsIpv6: Boolean,
    ) : TypedSatelliteResult
}

internal fun parseTypedSatellite(
    host: String?,
    httpsPort: String?,
    udpPort: String?,
): TypedSatelliteResult {
    val trimmedHost = host?.trim().orEmpty()
    val https = parsePort(httpsPort)
    val udp = parsePort(udpPort)
    val hostMissing = trimmedHost.isEmpty()
    val hostIsIpv6 = isIpv6Literal(trimmedHost)
    if (hostMissing || hostIsIpv6 || https == null || udp == null) {
        return TypedSatelliteResult.Rejected(
            hostMissing = hostMissing,
            httpsPortInvalid = https == null,
            udpPortInvalid = udp == null,
            hostIsIpv6 = hostIsIpv6,
        )
    }
    return TypedSatelliteResult.Accepted(TypedSatellite(trimmedHost, https, udp))
}

internal fun parsePort(text: String?): Int? {
    val port = text?.trim()?.toIntOrNull() ?: return null
    return if (port in MIN_PORT..MAX_PORT) port else null
}

internal fun isValidPin(text: CharSequence?): Boolean = !text.isNullOrEmpty()
