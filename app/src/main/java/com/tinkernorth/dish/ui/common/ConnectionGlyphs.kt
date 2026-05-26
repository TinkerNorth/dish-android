// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.view.MenuItem
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuItemCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
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

fun AppCompatActivity.paintConnectionMenuItem(
    item: MenuItem?,
    summary: ConnectionSummary?,
) {
    item ?: return
    val live = summary?.live
    val connected = live == LinkState.Connected
    item.setIcon(if (connected) R.drawable.ic_overlay_link else R.drawable.ic_overlay_link_off)
    val colorRes = live?.let(::dotColorForState) ?: R.color.colorMuted
    MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(getColor(colorRes)))
}

fun AppCompatActivity.showConnectionDialog(summary: ConnectionSummary?) {
    val kindLabel =
        when (summary?.kind) {
            ConnectionKind.SATELLITE -> getString(R.string.overlay_connection_kind_satellite)
            ConnectionKind.BLUETOOTH -> getString(R.string.overlay_connection_kind_bluetooth)
            null -> getString(R.string.overlay_status_unknown)
        }
    val stateLabel =
        summary?.let { statusChipText(this, it.live) }
            ?: getString(R.string.overlay_status_not_connected)
    val title =
        summary?.label?.takeIf { it.isNotBlank() }
            ?: getString(R.string.overlay_dialog_connection_title)
    val message =
        buildString {
            append(kindLabel)
            val detail = summary?.detail
            if (!detail.isNullOrBlank()) append('\n').append(detail)
            append("\n\n").append(stateLabel)
        }
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(R.string.action_close, null)
        .show()
}
