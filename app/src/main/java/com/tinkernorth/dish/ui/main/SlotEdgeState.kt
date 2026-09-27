// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState

internal fun LinkState.isAvailableForPicker(): Boolean =
    when (this) {
        LinkState.Connected, LinkState.Unstable -> true
        LinkState.Connecting,
        LinkState.Ready, LinkState.Found,
        LinkState.Saved, LinkState.Stale,
        -> false
    }

// Unstable is degraded but still routing, so it counts as live alongside Connected.
internal fun LinkState.isLiveLink(): Boolean = this == LinkState.Connected || this == LinkState.Unstable

// A Moonlight host is always offered. Its session is started BY the binding, so requiring a
// live link before it can be picked is circular: it can never be live until something binds to
// it, and nothing can bind to it until it is live.
internal fun connectionsVisibleInPicker(
    all: List<ConnectionSummary>,
    boundConnectionId: String?,
): List<ConnectionSummary> =
    all.filter {
        it.live.isAvailableForPicker() || it.kind == ConnectionKind.MOONLIGHT || it.id == boundConnectionId
    }

// The badge a bound slot's card can wear; NONE is the quiet default.
internal enum class EdgeState { NONE, HOST_LOST, INPUT_LOST, UNSTEADY }

// A Moonlight host is never "lost": there is no live link to lose, only remembered trust,
// and the session is started by the binding itself. Its state is reported in the binding
// screen where the actions that recover it live, so the dashboard stays quiet.
internal fun slotEdgeState(slot: ControllerSlot): EdgeState {
    val bound = slot.boundStatus
    if (bound == null || slot.boundConnectionId == null) return EdgeState.NONE
    if (slot.isDisconnecting) return EdgeState.INPUT_LOST
    if (bound.kind == ConnectionKind.MOONLIGHT) return EdgeState.NONE
    return when (bound.live) {
        LinkState.Unstable -> EdgeState.UNSTEADY
        LinkState.Connected -> EdgeState.NONE
        // Connecting (incl. a global reconnect in flight) keeps showing "lost" so the badge doesn't flicker off.
        else -> EdgeState.HOST_LOST
    }
}
