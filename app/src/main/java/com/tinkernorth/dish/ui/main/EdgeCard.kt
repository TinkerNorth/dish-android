// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.LinkState

// The edge banner's primary button. RECONNECT_PENDING is the spinner: a reconnect is already
// in flight somewhere, so offering another would only stack them.
internal enum class EdgePrimary { RECONNECT, RECONNECT_PENDING, UNBIND, NONE }

internal enum class EdgeSecondary { CONFIGURE, DISMISS, NONE }

// Everything the edge banner over a bound card says and offers; the adapter only paints it.
internal data class EdgeCard(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val detailArg: String? = null,
    val countdownSec: Int? = null,
    val primary: EdgePrimary,
    val secondary: EdgeSecondary,
    @ColorRes val accentRes: Int,
)

// Null is the quiet card with no banner at all.
internal fun edgeCardFor(
    edge: EdgeState,
    row: ControllerRow,
): EdgeCard? =
    when (edge) {
        EdgeState.NONE -> null
        EdgeState.HOST_LOST -> hostLostCard(row)
        EdgeState.INPUT_LOST -> inputLostCard(row.slot)
        EdgeState.UNSTEADY -> UNSTEADY_CARD
    }

private fun hostLostCard(row: ControllerRow): EdgeCard {
    val reconnectInFlight = row.connections.any { it.live == LinkState.Connecting }
    val primary = if (reconnectInFlight) EdgePrimary.RECONNECT_PENDING else EdgePrimary.RECONNECT
    return EdgeCard(
        iconRes = R.drawable.ic_error,
        titleRes = R.string.binding_edge_host_lost_title,
        detailRes = R.string.binding_edge_host_lost_detail,
        detailArg = row.slot.boundStatus?.label ?: "",
        primary = primary,
        secondary = EdgeSecondary.CONFIGURE,
        accentRes = R.color.colorError,
    )
}

private fun inputLostCard(slot: ControllerSlot): EdgeCard =
    EdgeCard(
        iconRes = R.drawable.ic_usb,
        titleRes = R.string.binding_edge_input_lost_title,
        detailRes = R.string.binding_edge_input_lost_detail,
        countdownSec = slot.disconnectTimeLeft,
        primary = EdgePrimary.UNBIND,
        secondary = EdgeSecondary.NONE,
        accentRes = R.color.colorWarning,
    )

private val UNSTEADY_CARD =
    EdgeCard(
        iconRes = R.drawable.ic_warning,
        titleRes = R.string.binding_edge_unsteady_title,
        detailRes = R.string.binding_edge_unsteady_detail,
        primary = EdgePrimary.NONE,
        secondary = EdgeSecondary.DISMISS,
        accentRes = R.color.colorWarning,
    )
