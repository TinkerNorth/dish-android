// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.os.Build
import com.tinkernorth.dish.source.audio.MicIndicatorState

// One emission of everything the streaming service reads: the notification's facts and the
// foreground declaration's.
internal data class StreamingSnapshot(
    val streamingSlots: Int,
    val connections: List<ConnectionSummary>,
    val directClaims: Int,
    val micArmed: Boolean,
    val micState: MicIndicatorState,
)

internal enum class RefreshAction { STOP, REDECLARE, NOTIFY }

// A held Direct claim keeps the service alive on its own for the pad's eventual restore, and the
// microphone type only ever moves through another startForeground.
internal fun refreshActionFor(
    snapshot: StreamingSnapshot,
    micTypeHeld: Boolean,
): RefreshAction {
    val hasWork = snapshot.streamingSlots > 0 || snapshot.directClaims > 0
    if (!hasWork) return RefreshAction.STOP
    val micTypeMoved = snapshot.micArmed != micTypeHeld
    return if (micTypeMoved) RefreshAction.REDECLARE else RefreshAction.NOTIFY
}

internal fun primaryLabelFor(connections: List<ConnectionSummary>): String? =
    connections.firstOrNull { it.live == LinkState.Connected }?.label

// With no active stream the service is alive only for held Direct claims, so a zero count reads
// as the claim-hold body rather than "0 streaming".
internal enum class StreamingBodyKind { STREAMING, USB_HOLD }

internal fun streamingBodyKind(streamingSlots: Int): StreamingBodyKind =
    if (streamingSlots > 0) StreamingBodyKind.STREAMING else StreamingBodyKind.USB_HOLD

internal data class SessionsToStop(
    val satelliteIds: List<String>,
    val bluetoothIds: List<String>,
)

internal fun sessionsToStop(summaries: List<ConnectionSummary>): SessionsToStop {
    val connected = summaries.filter { it.live == LinkState.Connected }
    val satelliteIds = connected.filter { it.kind == ConnectionKind.SATELLITE }.map { it.id }
    val bluetoothIds = connected.filter { it.kind == ConnectionKind.BLUETOOTH }.map { it.id }
    return SessionsToStop(satelliteIds, bluetoothIds)
}

// The type sets to declare, in order: the first the OS grants is the one held. A denied microphone
// type falls back to the type this service can always hold rather than taking the session down.
internal enum class ForegroundDeclaration(
    val micArmed: Boolean,
) {
    CONNECTED_DEVICE_AND_MICROPHONE(true),
    CONNECTED_DEVICE_ONLY(false),
}

internal fun foregroundDeclarationsFor(micArmed: Boolean): List<ForegroundDeclaration> =
    if (micArmed) {
        listOf(ForegroundDeclaration.CONNECTED_DEVICE_AND_MICROPHONE, ForegroundDeclaration.CONNECTED_DEVICE_ONLY)
    } else {
        listOf(ForegroundDeclaration.CONNECTED_DEVICE_ONLY)
    }

// The values of ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, _CONNECTED_DEVICE (API 29) and _MICROPHONE
// (API 30): javac inlines either spelling, and lint cannot see an sdkInt parameter as their API gate.
internal const val FOREGROUND_TYPE_NONE = 0
internal const val FOREGROUND_TYPE_CONNECTED_DEVICE = 16
internal const val FOREGROUND_TYPE_MICROPHONE = 128

// CONNECTED_DEVICE is what the session IS; MICROPHONE rides only while a mic-enabled binding is
// armed, not delivering, since a while-in-use type re-taken after a mute may not be granted again.
internal fun foregroundServiceTypes(micArmed: Boolean): Int {
    val micType = if (micArmed) FOREGROUND_TYPE_MICROPHONE else FOREGROUND_TYPE_NONE
    return FOREGROUND_TYPE_CONNECTED_DEVICE or micType
}

// Typed foreground services arrived in 29 with the connected-device type and gained the microphone
// type in 30; below 29 ServiceCompat ignores the value.
internal fun foregroundServiceTypesForThisApi(
    sdkInt: Int,
    micArmed: Boolean,
): Int =
    when {
        sdkInt >= Build.VERSION_CODES.R -> foregroundServiceTypes(micArmed)
        sdkInt >= Build.VERSION_CODES.Q -> FOREGROUND_TYPE_CONNECTED_DEVICE
        else -> FOREGROUND_TYPE_NONE
    }
