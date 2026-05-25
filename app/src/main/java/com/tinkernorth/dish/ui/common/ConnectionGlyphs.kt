// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState

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

@androidx.annotation.ColorRes
fun dotColorForState(state: LinkState): Int =
    when (state) {
        LinkState.Connected -> R.color.colorSuccess
        LinkState.Connecting, LinkState.Unstable -> R.color.colorPrimary
        LinkState.Stale -> R.color.colorWarning
        else -> R.color.colorMuted
    }

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

fun statusChipText(
    ctx: Context,
    state: LinkState,
): String = ctx.getString(statusChipTextRes(state))
