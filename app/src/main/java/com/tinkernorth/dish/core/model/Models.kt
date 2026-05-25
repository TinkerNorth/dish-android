// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Not a wire field — assigned client-side by the discovery merge.
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
    // Pairing and the connection API share the satellite HTTPS server on 9443.
    val pairPort: Int = 9443,
    val httpPort: Int = 9443,
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

data class ControllerEntry(
    val id: Int,
    val name: String,
    val controllerIndex: Int,
    val isDisconnected: Boolean = false,
    val disconnectTimeLeft: Int = 0,
)
