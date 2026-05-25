// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R

enum class MotionIndicatorState(
    @param:StringRes val labelRes: Int,
    @param:ColorRes val dotColorRes: Int,
) {
    STREAMING(R.string.motion_streaming, R.color.colorSuccess),
    PAUSED(R.string.motion_paused, R.color.colorWarning),
    STALLED(R.string.motion_stalled, R.color.colorWarning),
    USER_DISABLED(R.string.motion_user_disabled, R.color.colorMuted),
    NOT_FORWARDED(R.string.motion_not_forwarded, R.color.colorMuted),
    NO_HOST_SINK(R.string.motion_no_host_sink, R.color.colorMuted),
    BACKEND_BROKEN(R.string.motion_backend_broken, R.color.colorWarning),
    UNAVAILABLE(R.string.motion_unavailable, R.color.colorMuted),
    ;

    val hasDetail: Boolean
        get() =
            this == UNAVAILABLE ||
                this == NOT_FORWARDED ||
                this == STALLED ||
                this == USER_DISABLED ||
                this == NO_HOST_SINK ||
                this == BACKEND_BROKEN

    companion object {
        // Precedence: UNAVAILABLE > USER_DISABLED > NOT_FORWARDED > NO_HOST_SINK > BACKEND_BROKEN > STALLED > STREAMING > PAUSED.
        @Suppress("LongParameterList")
        fun of(
            isAvailable: Boolean,
            isStreaming: Boolean,
            connectionCarriesMotion: Boolean,
            connectionConnected: Boolean,
            userEnabled: Boolean = true,
            hostHasSinkForType: Boolean = true,
            satelliteBackendOk: Boolean? = null,
            isStalled: Boolean = false,
        ): MotionIndicatorState =
            when {
                !isAvailable -> UNAVAILABLE
                !userEnabled -> USER_DISABLED
                !connectionCarriesMotion -> NOT_FORWARDED
                !hostHasSinkForType -> NO_HOST_SINK
                satelliteBackendOk == false -> BACKEND_BROKEN
                isStreaming && connectionConnected && isStalled -> STALLED
                isStreaming && connectionConnected -> STREAMING
                else -> PAUSED
            }
    }
}
