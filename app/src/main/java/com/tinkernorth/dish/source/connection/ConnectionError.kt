// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

/**
 * Why a satellite connection failed, as a kind rather than words: the UI turns each case into a
 * string resource in the user's language. A reason the satellite stated rides along as it sent it.
 */
sealed interface ConnectionError {
    data object ServerUnreachable : ConnectionError

    data object RepairNeeded : ConnectionError

    data object IdentityChanged : ConnectionError

    data object WireFailed : ConnectionError

    // The address is IPv6; the satellite and the dish's UDP socket speak IPv4 only.
    data object Ipv6Unsupported : ConnectionError

    data object SatelliteUpdateRequired : ConnectionError

    data object AppUpdateRequired : ConnectionError

    data object ApprovalDeclined : ConnectionError

    data object ApprovalTimedOut : ConnectionError

    data object PairingFailed : ConnectionError

    data class PairingRefused(
        val reason: String,
    ) : ConnectionError

    data object SessionFailed : ConnectionError

    data class SessionRefused(
        val reason: String,
    ) : ConnectionError

    data class ApplyFailed(
        val serverName: String,
        val failures: String,
    ) : ConnectionError
}

internal fun pairingRefusal(reason: String?): ConnectionError {
    val stated = reason?.takeIf { it.isNotBlank() } ?: return ConnectionError.PairingFailed
    return ConnectionError.PairingRefused(stated)
}

internal fun sessionRefusal(reason: String?): ConnectionError {
    val stated = reason?.takeIf { it.isNotBlank() } ?: return ConnectionError.SessionFailed
    return ConnectionError.SessionRefused(stated)
}
