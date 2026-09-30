// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R

internal fun Context.streamLines(b: BindingDiag): List<String> {
    val lines = mutableListOf<String>()
    b.packetsSent?.let { lines += diagKv(R.string.diagnostics_packets_sent, it.toString()) }
    b.motionSent?.let { lines += diagKv(R.string.diagnostics_motion_sent, it.toString()) }
    lines += diagKv(R.string.diagnostics_touchpad_mode, b.touchpadMode)
    lines += diagKv(R.string.diagnostics_battery_source, getString(batterySourceRes(b.batterySource)))
    lines += diagKv(R.string.setup_cap_mic, getString(micStateRes(b.micState)))
    lines += diagKv(R.string.setup_cap_speaker, speakerText(b))
    return lines
}

@StringRes
private fun batterySourceRes(source: BatterySource): Int =
    when (source) {
        BatterySource.PHONE -> R.string.diagnostics_battery_phone
        BatterySource.PAD -> R.string.diagnostics_battery_pad
        BatterySource.LOWEST_OF_BOTH -> R.string.diagnostics_battery_lowest
    }

@StringRes
private fun micStateRes(state: MicSlotState): Int =
    when (state) {
        MicSlotState.OFF -> R.string.diagnostics_mic_off
        MicSlotState.ARMED_MUTED -> R.string.diagnostics_mic_muted
        MicSlotState.CAPTURING -> R.string.diagnostics_mic_capturing
    }

// Drops are appended only when there are some, so a clean stream reads as one word.
private fun Context.speakerText(b: BindingDiag): String {
    val playing =
        getString(if (b.speakerPlaying) R.string.diagnostics_speaker_playing else R.string.diagnostics_speaker_idle)
    val hasDrops = b.speakerDropped > 0
    if (!hasDrops) return playing
    return getString(
        R.string.diagnostics_joined,
        playing,
        resources.getQuantityString(
            R.plurals.diagnostics_dropped_samples,
            b.speakerDropped.pluralCount(),
            b.speakerDropped,
        ),
    )
}
