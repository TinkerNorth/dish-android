// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import androidx.annotation.DrawableRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.LinkState

/**
 * Single source of truth for the v6 brand glyph used to identify a
 * connection by its [kind] and current [state]. Picked from the
 * `ic_satellite{,_connected,_off}` / `ic_bluetooth{,_connected,_searching,_off}`
 * family — same icons used by the dish-website, dish-mac, and the satellite
 * dashboard, so a user moving between clients sees the same iconography.
 *
 * Centralised here so the dashboard's slot card, the Connections list rows,
 * the bind-picker rows, and the notification banners all read the same.
 * Previously this mapping lived in two places (ConnectionsActivity +
 * ControllerAdapter) and drifted whenever a new state landed.
 *
 * Mapping rules:
 *  - Connected → "connected" variant (filled cyan).
 *  - Connecting (Bluetooth only) → "searching" radar variant.
 *  - Saved / Stale → "off" variant (greyed silhouette).
 *  - Found / Ready / Connecting (Satellite) / Unstable → base variant.
 */
@DrawableRes
fun glyphForConnection(
    kind: ConnectionKind,
    state: LinkState,
): Int =
    when (kind) {
        ConnectionKind.SATELLITE ->
            when (state) {
                LinkState.Connected -> R.drawable.ic_satellite_connected
                LinkState.Saved, LinkState.Stale -> R.drawable.ic_satellite_off
                else -> R.drawable.ic_satellite
            }
        ConnectionKind.BLUETOOTH ->
            when (state) {
                LinkState.Connected -> R.drawable.ic_bluetooth_connected
                LinkState.Connecting -> R.drawable.ic_bluetooth_searching
                LinkState.Saved, LinkState.Stale -> R.drawable.ic_bluetooth_off
                else -> R.drawable.ic_bluetooth
            }
    }

/**
 * Single source of truth for the dot color attached to a connection's status
 * chip. Mirrors the chip text mapping in [statusChipText] — Connected = green,
 * in-flight = primary cyan, Stale = warning amber, everything else muted.
 */
@androidx.annotation.ColorRes
fun dotColorForState(state: LinkState): Int =
    when (state) {
        LinkState.Connected -> R.color.colorSuccess
        LinkState.Connecting, LinkState.Unstable -> R.color.colorPrimary
        LinkState.Stale -> R.color.colorWarning
        else -> R.color.colorMuted
    }

/**
 * User-facing chip text for a [LinkState]. Lifted out of
 * `ConnectionsActivity.statusText` so the same vocabulary surfaces wherever
 * a connection is rendered.
 */
fun statusChipText(state: LinkState): String =
    when (state) {
        LinkState.Found -> "Found"
        LinkState.Stale -> "Needs pairing"
        LinkState.Saved -> "Offline"
        LinkState.Ready -> "Ready"
        LinkState.Connecting -> "Connecting…"
        LinkState.Connected -> "Online"
        LinkState.Unstable -> "Unsteady"
    }
