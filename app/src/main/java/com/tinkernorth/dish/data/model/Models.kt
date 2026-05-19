// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.model

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Which discovery path surfaced a satellite. mDNS / Bonjour is the modern
 * path; [BROADCAST] is the legacy UDP beacon; [BOTH] means it answered on
 * each. Not a wire field — assigned client-side by the discovery merge.
 *
 * [labelRes] is a string resource (not a hardcoded literal) so the
 * connections-list label is localisable.
 */
enum class DiscoverySource(
    @param:StringRes val labelRes: Int,
) {
    BROADCAST(R.string.discovery_source_broadcast),
    MDNS(R.string.discovery_source_mdns),
    BOTH(R.string.discovery_source_both),
}

@Serializable
data class DiscoveredServer(
    val name: String = "",
    val ip: String = "",
    val udpPort: Int = 9876,
    // Pairing (POST /api/pair) and the connection API share the satellite's
    // single HTTPS/TLS client server on 9443; these defaults apply when a
    // discovery beacon omits the explicit port fields.
    val pairPort: Int = 9443,
    val httpPort: Int = 9443,
    // Discovery path this server was heard on. @Transient → never on the
    // wire; a decoded beacon keeps the BROADCAST default.
    @Transient val source: DiscoverySource = DiscoverySource.BROADCAST,
)

@Serializable
data class PairResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val sharedKey: String? = null,
)

@Serializable
data class ConnectResponse(
    val connectionId: String? = null,
    val token: String? = null,
    val error: String? = null,
)

/** Per-controller state tracked by the dashboard. */
data class ControllerEntry(
    val id: Int,
    val name: String,
    val controllerIndex: Int,
    val isDisconnected: Boolean = false,
    val disconnectTimeLeft: Int = 0,
)
