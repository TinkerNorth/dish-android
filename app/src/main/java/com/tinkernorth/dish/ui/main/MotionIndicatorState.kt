// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.sensor.MotionStreamState

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

    val hasDetail: Boolean get() = motionDetailRes(this) != null
}

// Precedence: UNAVAILABLE > USER_DISABLED > NOT_FORWARDED > NO_HOST_SINK > BACKEND_BROKEN > STALLED > STREAMING > PAUSED.
fun motionIndicatorStateOf(
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
        !isAvailable -> MotionIndicatorState.UNAVAILABLE
        !userEnabled -> MotionIndicatorState.USER_DISABLED
        !connectionCarriesMotion -> MotionIndicatorState.NOT_FORWARDED
        !hostHasSinkForType -> MotionIndicatorState.NO_HOST_SINK
        satelliteBackendOk == false -> MotionIndicatorState.BACKEND_BROKEN
        isStreaming && connectionConnected && isStalled -> MotionIndicatorState.STALLED
        isStreaming && connectionConnected -> MotionIndicatorState.STREAMING
        else -> MotionIndicatorState.PAUSED
    }

/**
 * Translate the three live overlay inputs into the boolean flags of
 * [motionIndicatorStateOf]. Pure so the toolbar-paint decision is testable
 * outside the Activity.
 */
fun motionIndicatorFor(
    summary: ConnectionSummary?,
    capability: SlotCapabilities,
    source: MotionStreamState,
): MotionIndicatorState {
    // A null summary (connection not resolved yet) is treated as motion-capable but
    // not-yet-connected: PAUSED is rendered until the kind + liveness resolve, then the next
    // paint self-corrects.
    val carriesMotion = summary?.kind != ConnectionKind.BLUETOOTH
    val connected = summary?.live == LinkState.Connected
    val isAvailable = source != MotionStreamState.Disabled
    val isStreaming =
        source == MotionStreamState.Streaming ||
            source == MotionStreamState.Stalled
    val isStalled = source == MotionStreamState.Stalled
    // runtimeDown carries MOTION only when the satellite reported its backend down; map that
    // back to the false/null the indicator's backend branch expects (null = no observation).
    val satelliteBackendOk = if (Feature.MOTION in capability.runtimeDown) false else null
    return motionIndicatorStateOf(
        isAvailable = isAvailable,
        isStreaming = isStreaming,
        connectionCarriesMotion = carriesMotion,
        connectionConnected = connected,
        userEnabled = capability.userWants(Feature.MOTION),
        hostHasSinkForType = capability.typeOk(Feature.MOTION),
        satelliteBackendOk = satelliteBackendOk,
        isStalled = isStalled,
    )
}

// The readout's motion entry shows a rate (or the pending glyph) in the states where motion
// is user-facing on, and Off in the muted indicator states. STALLED and PAUSED count as on: no
// samples flow there, so the entry reads pending rather than a misleading Off.
fun motionReadoutOn(state: MotionIndicatorState?): Boolean =
    when (state) {
        MotionIndicatorState.STREAMING,
        MotionIndicatorState.STALLED,
        MotionIndicatorState.PAUSED,
        -> true
        MotionIndicatorState.USER_DISABLED,
        MotionIndicatorState.NOT_FORWARDED,
        MotionIndicatorState.NO_HOST_SINK,
        MotionIndicatorState.BACKEND_BROKEN,
        MotionIndicatorState.UNAVAILABLE,
        null,
        -> false
    }

// The second paragraph of the motion dialog: every limit state explains itself, the live
// ones have nothing to add.
@StringRes
fun motionDetailRes(state: MotionIndicatorState): Int? =
    when (state) {
        MotionIndicatorState.UNAVAILABLE -> R.string.motion_unavailable_detail
        MotionIndicatorState.NOT_FORWARDED -> R.string.motion_not_forwarded_detail
        MotionIndicatorState.STALLED -> R.string.motion_stalled_detail
        MotionIndicatorState.USER_DISABLED -> R.string.motion_user_disabled_detail
        MotionIndicatorState.NO_HOST_SINK -> R.string.motion_no_host_sink_detail
        MotionIndicatorState.BACKEND_BROKEN -> R.string.motion_backend_broken_detail
        MotionIndicatorState.STREAMING, MotionIndicatorState.PAUSED -> null
    }
