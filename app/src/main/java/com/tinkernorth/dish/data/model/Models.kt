// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredServer(
    val name: String = "",
    val ip: String = "",
    val udpPort: Int = 9876,
    val pairPort: Int = 9878,
    val httpPort: Int = 9877,
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
