// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.setup

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.input.GamepadProfile
import com.tinkernorth.dish.source.usb.DirectClaimFailure

@StringRes
internal fun directFailureReasonRes(reason: DirectClaimFailure): Int =
    when (reason) {
        DirectClaimFailure.Busy -> R.string.path_reason_busy
        DirectClaimFailure.InitFailed -> R.string.path_reason_init_failed
        DirectClaimFailure.PermissionDenied -> R.string.path_reason_permission_denied
        DirectClaimFailure.Dropped -> R.string.path_needs_replug
    }

// The Moonlight caption names the host; a Bluetooth host has its type locked upstream and says so.
@StringRes
internal fun typeSubtitleRes(hostKind: ConnectionKind?): Int =
    when (hostKind) {
        ConnectionKind.MOONLIGHT -> R.string.ml_type_caption
        ConnectionKind.BLUETOOTH -> R.string.setup_cfg_type_locked_subtitle
        ConnectionKind.SATELLITE, null -> R.string.setup_cfg_type_subtitle
    }

internal data class BtHostTypeCopy(
    @StringRes val titleRes: Int,
    @StringRes val badgeRes: Int,
    @DrawableRes val glyphRes: Int,
)

internal fun btHostTypeCopy(profile: GamepadProfile): BtHostTypeCopy =
    when (profile) {
        GamepadProfile.XBOX -> BtHostTypeCopy(R.string.setup_bth_type_xbox, R.string.setup_bth_badge_xbox, R.drawable.ic_ctrl_xbox)
        GamepadProfile.PLAYSTATION ->
            BtHostTypeCopy(R.string.setup_bth_type_playstation, R.string.setup_bth_badge_playstation, R.drawable.ic_ctrl_ds4)
    }

@StringRes
internal fun hostStatusRes(link: LinkState): Int =
    when (link) {
        LinkState.Connecting -> R.string.setup_conn_status_reconnecting
        LinkState.Connected, LinkState.Unstable -> R.string.setup_conn_status_connected
        LinkState.Stale -> R.string.setup_conn_status_needs_pairing
        LinkState.Ready, LinkState.Found, LinkState.Saved -> R.string.setup_conn_status_ready
    }
