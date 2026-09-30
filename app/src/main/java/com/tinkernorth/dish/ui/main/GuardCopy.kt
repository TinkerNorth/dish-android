// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R

// What the guard scrim says: icon and its colour, the title, and the detail line with the
// one argument some of them take (the host's label).
internal data class GuardCopy(
    @DrawableRes val iconRes: Int,
    @ColorRes val colorRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val detailArg: String? = null,
)

internal fun guardCopy(ui: OverlayGuardUi): GuardCopy =
    GuardCopy(
        iconRes = guardIconRes(ui.kind),
        colorRes = guardColorRes(ui.kind),
        titleRes = guardTitleRes(ui.kind),
        detailRes = guardDetailRes(ui),
        detailArg = guardDetailArg(ui),
    )

@DrawableRes
private fun guardIconRes(kind: GuardKind): Int =
    when (kind) {
        GuardKind.HOST_LOST, GuardKind.GONE -> R.drawable.ic_error
        GuardKind.RECONNECTING -> R.drawable.ic_refresh
        GuardKind.UNBOUND -> R.drawable.ic_link_off
        GuardKind.NONE, GuardKind.UNPLUGGED, GuardKind.DEPARTED -> R.drawable.ic_gamepad
    }

@ColorRes
private fun guardColorRes(kind: GuardKind): Int =
    when (kind) {
        GuardKind.HOST_LOST, GuardKind.GONE -> R.color.colorError
        GuardKind.RECONNECTING -> R.color.colorPrimary
        GuardKind.NONE, GuardKind.UNPLUGGED, GuardKind.DEPARTED, GuardKind.UNBOUND -> R.color.colorWarning
    }

@StringRes
private fun guardTitleRes(kind: GuardKind): Int =
    when (kind) {
        GuardKind.HOST_LOST -> R.string.binding_edge_host_lost_title
        GuardKind.RECONNECTING -> R.string.chip_status_connecting
        GuardKind.UNPLUGGED, GuardKind.DEPARTED -> R.string.binding_edge_input_lost_title
        GuardKind.UNBOUND -> R.string.overlay_guard_unbound_title
        GuardKind.NONE, GuardKind.GONE -> R.string.overlay_guard_gone_title
    }

// A lost or reconnecting link blames what is most likely at fault: the network when Wi-Fi is
// down, the host's own radio or session otherwise, and the host by name as the last resort.
@StringRes
private fun guardDetailRes(ui: OverlayGuardUi): Int =
    when (ui.kind) {
        GuardKind.HOST_LOST, GuardKind.RECONNECTING -> linkDetailRes(ui.detail)
        GuardKind.UNPLUGGED -> R.string.overlay_guard_replug_detail
        GuardKind.DEPARTED -> R.string.overlay_guard_departed_detail
        GuardKind.UNBOUND -> R.string.overlay_guard_unbound_detail
        GuardKind.NONE, GuardKind.GONE -> R.string.overlay_guard_gone_detail
    }

@StringRes
private fun linkDetailRes(detail: GuardDetail): Int =
    when (detail) {
        GuardDetail.WIFI_DOWN -> R.string.overlay_guard_wifi_detail
        GuardDetail.BLUETOOTH_HOST -> R.string.overlay_guard_bt_detail
        GuardDetail.MOONLIGHT_SESSION -> R.string.overlay_guard_ml_detail
        GuardDetail.GENERIC -> R.string.binding_edge_host_lost_detail
    }

private fun guardDetailArg(ui: OverlayGuardUi): String? =
    when (ui.kind) {
        GuardKind.UNBOUND -> ui.hostLabel
        GuardKind.HOST_LOST, GuardKind.RECONNECTING -> ui.hostLabel.takeIf { ui.detail == GuardDetail.GENERIC }
        GuardKind.NONE, GuardKind.UNPLUGGED, GuardKind.DEPARTED, GuardKind.GONE -> null
    }
