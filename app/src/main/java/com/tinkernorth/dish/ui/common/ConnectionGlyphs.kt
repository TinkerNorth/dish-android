// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState

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
 * String resource id of the user-facing chip text for a [LinkState]. Lifted
 * out of `ConnectionsActivity.statusText` so the same vocabulary surfaces
 * wherever a connection is rendered. Returns a resource id (rather than a
 * resolved String) so this helper stays pure / testable without a Context.
 */
@StringRes
fun statusChipTextRes(state: LinkState): Int =
    when (state) {
        LinkState.Found -> R.string.chip_status_found
        LinkState.Stale -> R.string.chip_status_needs_pairing
        LinkState.Saved -> R.string.chip_status_offline
        LinkState.Ready -> R.string.chip_status_ready
        LinkState.Connecting -> R.string.chip_status_connecting
        LinkState.Connected -> R.string.chip_status_online
        LinkState.Unstable -> R.string.chip_status_unstable
    }

/** Convenience wrapper for callers that already have a Context in hand. */
fun statusChipText(
    ctx: Context,
    state: LinkState,
): String = ctx.getString(statusChipTextRes(state))
