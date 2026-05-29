// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.usb.PathMode
import com.tinkernorth.dish.source.usb.PathReason

// Translates a PathMode + PathReason into a user-facing label + optional "why?" subtitle. Names
// are intentionally non-technical so the dashboard reads "Direct mode" rather than "USB host fast
// lane via libusb evdev" or "JNI-routed framework input".
data class PathBadge(
    val label: String,
    val subtitle: String?,
    val isDirect: Boolean,
    val actionable: Boolean = false,
)

object PathBadgeMapper {
    fun map(
        ctx: Context,
        mode: PathMode,
        reason: PathReason,
        isOnScreen: Boolean,
        pollRateHz: Int = 0,
    ): PathBadge {
        if (isOnScreen) {
            return PathBadge(
                label = ctx.getString(R.string.path_label_standard),
                subtitle = ctx.getString(R.string.path_reason_onscreen),
                isDirect = false,
            )
        }
        return when (mode) {
            PathMode.Direct -> {
                val label =
                    if (pollRateHz > 0) {
                        ctx.getString(R.string.path_label_direct_with_rate, pollRateHz)
                    } else {
                        ctx.getString(R.string.path_label_direct)
                    }
                PathBadge(
                    label = label,
                    subtitle = ctx.getString(R.string.path_direct_explainer),
                    isDirect = true,
                )
            }
            PathMode.Routed ->
                PathBadge(
                    label = ctx.getString(R.string.path_label_standard),
                    subtitle = reasonText(ctx, reason),
                    isDirect = false,
                    actionable = reason == PathReason.Eligible || reason == PathReason.PermissionDenied,
                )
        }
    }

    private fun reasonText(
        ctx: Context,
        reason: PathReason,
    ): String? {
        @StringRes val resId =
            when (reason) {
                PathReason.None -> R.string.path_reason_default
                PathReason.Bluetooth -> R.string.path_reason_bluetooth
                PathReason.OnScreen -> R.string.path_reason_onscreen
                PathReason.Eligible -> R.string.path_action_try_direct
                PathReason.PermissionDenied -> R.string.path_action_try_direct
                PathReason.UnknownModel -> R.string.path_reason_unknown_model
                PathReason.Busy -> R.string.path_reason_busy
                PathReason.InitFailed -> R.string.path_reason_init_failed
                PathReason.Detached -> R.string.path_reason_detached
                PathReason.SupportedNoFastPathYet -> R.string.path_reason_not_yet
            }
        return ctx.getString(resId)
    }
}
