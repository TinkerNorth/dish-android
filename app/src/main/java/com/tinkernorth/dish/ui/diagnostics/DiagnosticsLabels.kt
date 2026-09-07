// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.ui.common.bundledControllerTypeLabelRes
import com.tinkernorth.dish.ui.common.featureLabelRes
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes

private const val SEPARATOR = " · "

internal fun Context.kindLabel(kind: ConnectionKind): String =
    getString(
        when (kind) {
            ConnectionKind.SATELLITE -> R.string.overlay_connection_kind_satellite
            ConnectionKind.BLUETOOTH -> R.string.overlay_connection_kind_bluetooth
            ConnectionKind.MOONLIGHT -> R.string.overlay_connection_kind_moonlight
        },
    )

internal fun Context.transportLabel(diag: ControllerDiag): String =
    getString(
        when {
            diag.transport == Transport.Usb && diag.isUsbSynthetic -> R.string.diagnostics_transport_usb_direct
            diag.transport == Transport.Usb -> R.string.diagnostics_transport_usb_standard
            else -> R.string.diagnostics_transport_bluetooth
        },
    )

internal fun Context.controllerStateLabel(state: ControllerDiagState): String =
    getString(
        when (state) {
            ControllerDiagState.NEEDS_REPLUG -> R.string.diagnostics_state_needs_replug
            ControllerDiagState.TRANSITIONING -> R.string.diagnostics_state_transitioning
            ControllerDiagState.DISCONNECTING -> R.string.diagnostics_state_disconnecting
            ControllerDiagState.CONNECTED -> R.string.diagnostics_state_connected
        },
    )

internal fun Context.hzLabel(hz: Int): String {
    if (hz <= 0) return getString(R.string.diagnostics_unknown)
    return getString(R.string.diagnostics_hz, hz)
}

internal fun Context.emulatedTypeLabel(
    kind: ConnectionKind,
    typeId: Int?,
    btProfile: String?,
): String? =
    when (kind) {
        ConnectionKind.SATELLITE -> typeId?.let { getString(bundledControllerTypeLabelRes(it)) }
        ConnectionKind.MOONLIGHT -> typeId?.let { getString(moonlightTypeLabelRes(it)) }
        ConnectionKind.BLUETOOTH -> btProfile
    }

internal fun Context.featureList(features: List<Feature>): String =
    features.mapNotNull { featureLabelRes(it) }.joinToString(SEPARATOR) { getString(it) }

internal fun Context.hostFeatureList(features: HostFeatureSet): String {
    val labels =
        buildList {
            if (features.rumbleReturn) add(R.string.setup_cap_rumble)
            if (features.mouseControl) add(R.string.binding_func_mouse)
            if (features.keyboardControl) add(R.string.diagnostics_feature_keyboard)
            if (features.controllerMic) add(R.string.setup_cap_mic)
            if (features.controllerSpeaker) add(R.string.setup_cap_speaker)
        }
    if (labels.isEmpty()) return getString(R.string.diagnostics_none)
    return labels.joinToString(SEPARATOR) { getString(it) }
}
