// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary

// Where a phone pointer frame (touchpad or mouse) goes for the overlay's connection.
internal enum class PointerRoute { NONE, SATELLITE, MOONLIGHT }

// No summary or a dead link routes nowhere; a Bluetooth host carries no pointer at all.
internal fun pointerRouteFor(summary: ConnectionSummary?): PointerRoute {
    if (summary == null) return PointerRoute.NONE
    if (!summary.live.isLiveLink()) return PointerRoute.NONE
    return when (summary.kind) {
        ConnectionKind.SATELLITE -> PointerRoute.SATELLITE
        ConnectionKind.MOONLIGHT -> PointerRoute.MOONLIGHT
        ConnectionKind.BLUETOOTH -> PointerRoute.NONE
    }
}

// Satellite frames are lossy UDP state, so the pacer re-asserts them. Moonlight pointer
// packets are edge events on ENet's reliable channel; replaying them would replay clicks.
internal fun pointerResendAllowed(summary: ConnectionSummary?): Boolean = pointerRouteFor(summary) == PointerRoute.SATELLITE
