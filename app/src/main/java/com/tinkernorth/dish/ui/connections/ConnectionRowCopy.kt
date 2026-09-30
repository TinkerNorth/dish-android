// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.LinkState

internal enum class RowAction { DISCONNECT, CONNECTING, REPAIR, CONNECT }

// The primary button offers whatever the link state leaves to do: a live link disconnects,
// a stale one repairs, and a connecting one only shows its spinner.
internal fun primaryActionFor(live: LinkState): RowAction =
    when (live) {
        LinkState.Connected, LinkState.Unstable -> RowAction.DISCONNECT
        LinkState.Connecting -> RowAction.CONNECTING
        LinkState.Stale -> RowAction.REPAIR
        LinkState.Saved, LinkState.Ready, LinkState.Found -> RowAction.CONNECT
    }

internal data class MoonlightRowCopy(
    @StringRes val primaryLabelRes: Int,
    val inUse: Boolean,
)

// A host carrying controllers offers the way out of its session and says how many are on it;
// an idle one offers to pair, or to re-pair when it already holds a pairing.
internal fun moonlightRowCopy(row: MoonlightRow.Known): MoonlightRowCopy {
    val inUse = row.controllerCount > 0
    val primaryLabelRes = if (inUse) R.string.ml_action_quit_session else moonlightPairActionRes(row.trust)
    return MoonlightRowCopy(primaryLabelRes = primaryLabelRes, inUse = inUse)
}
