// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.MoonlightXml

/**
 * How much a Moonlight host is trusted, as of the last time we asked it. There is
 * no bidirectional liveness in this protocol: pairing is one-time trust, the host
 * never tells us it revoked one, and a successful mutual-TLS call is itself the
 * only proof. So this is a remembered word verified lazily, never a live link.
 */
enum class MoonlightTrustState { CHECKING, PAIRED, NOT_PAIRED, UNREACHABLE, REMEMBERED, TRUST_LOST, REPLACED }

data class MoonlightProbe(
    val trust: MoonlightTrustState = MoonlightTrustState.CHECKING,
    val apps: List<MoonlightXml.App> = emptyList(),
    val appsFetched: Boolean = false,
    val appsFailed: Boolean = false,
    val ownSession: Boolean = false,
    val currentAppId: String? = null,
)
