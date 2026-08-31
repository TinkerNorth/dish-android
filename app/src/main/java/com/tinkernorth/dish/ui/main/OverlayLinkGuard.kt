// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.system.NetworkState

internal enum class GuardKind { NONE, UNPLUGGED, RECONNECTING, HOST_LOST, DEPARTED, UNBOUND, GONE }

internal enum class GuardDetail { GENERIC, WIFI_DOWN, BLUETOOTH_HOST, MOONLIGHT_SESSION }

// Presence of the physical controller behind an overlay's slot; null for slots with no
// controller (the on-screen pad's virtual slot).
data class SlotDeviceState(
    val present: Boolean,
    val disconnectingSecLeft: Int? = null,
    val transitioning: Boolean = false,
    val needsReplug: Boolean = false,
)

internal data class OverlayGuardUi(
    val kind: GuardKind,
    val hostLabel: String = "",
    val countdownSec: Int? = null,
    val showReconnect: Boolean = false,
    val detail: GuardDetail = GuardDetail.GENERIC,
    // Terminal states: nothing on this screen can recover them, so it closes itself.
    val autoClose: Boolean = false,
)

// The overlay keeps sending only while the link routes; everything else must surface.
// Priority: a dead connection outranks a dead controller outranks a dead link, because
// each earlier state makes the later checks meaningless. A device mid path-switch is
// left alone: the twin swap resolves by itself and the sweep covers the failure case.
internal fun overlayGuardFor(
    summary: ConnectionSummary?,
    boundConnectionId: String?,
    connectionId: String,
    device: SlotDeviceState?,
    network: NetworkState,
): OverlayGuardUi {
    summary ?: return OverlayGuardUi(GuardKind.GONE, autoClose = true)
    val deviceGuard = deviceGuardFor(device, summary.label)
    return when {
        deviceGuard != null -> deviceGuard
        boundConnectionId != connectionId -> OverlayGuardUi(GuardKind.UNBOUND, hostLabel = summary.label, autoClose = true)
        summary.live.isLiveLink() -> OverlayGuardUi(GuardKind.NONE)
        else -> linkGuardFor(summary, network)
    }
}

private fun deviceGuardFor(
    device: SlotDeviceState?,
    label: String,
): OverlayGuardUi? {
    if (device == null || device.transitioning) return null
    return when {
        !device.present -> OverlayGuardUi(GuardKind.DEPARTED, hostLabel = label, autoClose = true)
        device.needsReplug -> OverlayGuardUi(GuardKind.UNPLUGGED, hostLabel = label)
        device.disconnectingSecLeft != null ->
            OverlayGuardUi(GuardKind.UNPLUGGED, hostLabel = label, countdownSec = device.disconnectingSecLeft)
        else -> null
    }
}

private fun linkGuardFor(
    summary: ConnectionSummary,
    network: NetworkState,
): OverlayGuardUi {
    val detail =
        when {
            summary.kind == ConnectionKind.BLUETOOTH -> GuardDetail.BLUETOOTH_HOST
            network != NetworkState.WIFI -> GuardDetail.WIFI_DOWN
            summary.kind == ConnectionKind.MOONLIGHT -> GuardDetail.MOONLIGHT_SESSION
            else -> GuardDetail.GENERIC
        }
    return if (summary.live == LinkState.Connecting) {
        OverlayGuardUi(GuardKind.RECONNECTING, hostLabel = summary.label, detail = detail)
    } else {
        OverlayGuardUi(
            GuardKind.HOST_LOST,
            hostLabel = summary.label,
            showReconnect = summary.kind == ConnectionKind.SATELLITE,
            detail = detail,
        )
    }
}
