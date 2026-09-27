// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.content.res.ColorStateList
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuItemCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState

// The overlays' toolbar link item and the dialog behind it, drawn from the glyph mappers.

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
    val title =
        summary?.label?.takeIf { it.isNotBlank() }
            ?: getString(R.string.overlay_dialog_connection_title)
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(connectionDialogMessage(summary))
        .setPositiveButton(R.string.action_close, null)
        .show()
}

private fun AppCompatActivity.connectionKindLabel(summary: ConnectionSummary?): String =
    when (summary?.kind) {
        ConnectionKind.SATELLITE -> getString(R.string.overlay_connection_kind_satellite)
        ConnectionKind.BLUETOOTH -> getString(R.string.overlay_connection_kind_bluetooth)
        ConnectionKind.MOONLIGHT -> getString(R.string.overlay_connection_kind_moonlight)
        null -> getString(R.string.overlay_status_unknown)
    }

// Kind, then the host's own detail if it has one, then the live state on its own line.
private fun AppCompatActivity.connectionDialogMessage(summary: ConnectionSummary?): String {
    val stateLabel =
        summary?.let { statusChipText(this, it.live) }
            ?: getString(R.string.overlay_status_not_connected)
    return buildString {
        append(connectionKindLabel(summary))
        val detail = summary?.detail
        if (!detail.isNullOrBlank()) append('\n').append(detail)
        append("\n\n").append(stateLabel)
    }
}
