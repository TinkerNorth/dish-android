// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.ControllerApplyDto
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.ui.common.featureLabelRes
import com.tinkernorth.dish.ui.common.statusChipTextRes

private val CAPABILITY_ROWS =
    listOf(
        Feature.MOTION,
        Feature.TOUCHPAD,
        Feature.MOUSE,
        Feature.RUMBLE,
        Feature.TRIGGER_RUMBLE,
        Feature.LIGHTBAR,
        Feature.TRIGGER_EFFECTS,
        Feature.PLAYER_LEDS,
        Feature.MIC,
        Feature.SPEAKER,
        Feature.BATTERY,
    )

internal fun Context.bindingSections(
    b: BindingDiag,
    nowMs: Long,
): BindingSections =
    BindingSections(
        binding = bindingLines(b, nowMs),
        declared = declaredLines(b),
        capabilities = capabilityLines(b.caps),
        streams = streamLines(b),
        feedback = feedbackLines(b, nowMs),
        latency = latencyLines(b.latency),
    )

private fun Context.bindingLines(
    b: BindingDiag,
    nowMs: Long,
): List<String> {
    val lines = mutableListOf<String>()
    lines +=
        diagKv(R.string.diagnostics_host, getString(R.string.diagnostics_joined, b.host.label, getString(statusChipTextRes(b.host.live))))
    emulatedTypeLabel(b.host.kind, b.host.typeId, b.host.btProfile)?.let { lines += diagKv(R.string.binding_label_emulate, it) }
    b.host.slotIndex?.let { lines += diagKv(R.string.diagnostics_slot, it.toString()) }
    b.boundSinceMs?.let { lines += diagKv(R.string.diagnostics_bound_since, durationLabel(nowMs - it)) }
    return lines
}

private fun Context.declaredLines(b: BindingDiag): List<String> {
    val lines = mutableListOf<String>()
    val declared = b.declaredCaps?.let { caps -> featureList(declaredFeatures(caps)) }
    lines += diagKv(R.string.diagnostics_declared_caps, declared?.ifBlank { null } ?: getString(R.string.diagnostics_none))
    b.declaredTouchpadMode?.let { lines += diagKv(R.string.diagnostics_touchpad_declared, it) }
    b.host.registered?.let { lines += diagKv(R.string.diagnostics_confirmed, diagYesNo(it)) }
    b.host.streaming?.let { lines += diagKv(R.string.diagnostics_streaming_label, diagYesNo(it)) }
    b.applyResult?.let { lines += diagKv(R.string.diagnostics_apply_result, applyResultLabel(it)) }
    b.motionBackend?.let {
        lines +=
            diagKv(
                R.string.diagnostics_motion_backend,
                getString(if (it.backendOk) R.string.diagnostics_backend_ok else R.string.diagnostics_backend_down),
            )
    }
    return lines
}

private fun Context.applyResultLabel(result: String): String =
    when (result) {
        ControllerApplyDto.APPLY_OK -> getString(R.string.diagnostics_apply_ok)
        ControllerApplyDto.APPLY_REPLUG_FAILED -> getString(R.string.diagnostics_apply_replug_failed)
        else -> result
    }

internal fun Context.capabilityLines(caps: SlotCapabilities?): List<String> {
    caps ?: return listOf(getString(R.string.diagnostics_unknown))
    return CAPABILITY_ROWS.mapNotNull { feature ->
        val label = featureLabelRes(feature) ?: return@mapNotNull null
        val status =
            when {
                !caps.isAvailable(feature) -> R.string.diagnostics_cap_unavailable
                !caps.isEnabled(feature) -> R.string.diagnostics_cap_off
                feature in caps.live -> R.string.diagnostics_cap_live
                else -> R.string.diagnostics_cap_down
            }
        diagKv(label, getString(status))
    }
}

private fun Context.streamLines(b: BindingDiag): List<String> {
    val lines = mutableListOf<String>()
    b.packetsSent?.let { lines += diagKv(R.string.diagnostics_packets_sent, it.toString()) }
    b.motionSent?.let { lines += diagKv(R.string.diagnostics_motion_sent, it.toString()) }
    lines += diagKv(R.string.diagnostics_touchpad_mode, b.touchpadMode)
    val battery =
        when (b.batterySource) {
            BatterySource.PHONE -> R.string.diagnostics_battery_phone
            BatterySource.PAD -> R.string.diagnostics_battery_pad
            BatterySource.LOWEST_OF_BOTH -> R.string.diagnostics_battery_lowest
        }
    lines += diagKv(R.string.diagnostics_battery_source, getString(battery))
    val mic =
        when (b.micState) {
            MicSlotState.OFF -> R.string.diagnostics_mic_off
            MicSlotState.ARMED_MUTED -> R.string.diagnostics_mic_muted
            MicSlotState.CAPTURING -> R.string.diagnostics_mic_capturing
        }
    lines += diagKv(R.string.setup_cap_mic, getString(mic))
    val speaker = getString(if (b.speakerPlaying) R.string.diagnostics_speaker_playing else R.string.diagnostics_speaker_idle)
    val speakerValue =
        if (b.speakerDropped > 0) {
            getString(R.string.diagnostics_joined, speaker, getString(R.string.diagnostics_dropped_samples, b.speakerDropped))
        } else {
            speaker
        }
    lines += diagKv(R.string.setup_cap_speaker, speakerValue)
    return lines
}

private fun Context.feedbackLines(
    b: BindingDiag,
    nowMs: Long,
): List<String> {
    val lines = mutableListOf<String>()
    val target =
        when (b.rumbleTarget) {
            FeedbackTargetKind.PHONE -> R.string.diagnostics_target_phone
            FeedbackTargetKind.PAD_DIRECT -> R.string.diagnostics_target_pad
            FeedbackTargetKind.PAD_FRAMEWORK -> R.string.diagnostics_target_pad_framework
            FeedbackTargetKind.NONE -> R.string.diagnostics_target_none
        }
    lines += diagKv(R.string.diagnostics_rumble_target, getString(target))
    val last =
        b.feedback?.let {
            getString(R.string.diagnostics_feedback_value, getString(feedbackKindRes(it.lastKind)), agoLabel(nowMs, it.atMs), it.count)
        } ?: getString(R.string.diagnostics_none)
    lines += diagKv(R.string.diagnostics_last_feedback, last)
    return lines
}

private fun feedbackKindRes(kind: FeedbackKind): Int =
    when (kind) {
        FeedbackKind.RUMBLE -> R.string.setup_cap_rumble
        FeedbackKind.TRIGGER_RUMBLE -> R.string.setup_cap_trigger_rumble
        FeedbackKind.LIGHTBAR -> R.string.setup_cap_lightbar
        FeedbackKind.PLAYER_LEDS -> R.string.setup_cap_player_leds
        FeedbackKind.TRIGGER_EFFECTS -> R.string.setup_cap_trigger_effects
        FeedbackKind.MIC_LED -> R.string.inspector_mic_lamp
    }

internal fun Context.latencyLines(estimate: LatencyEstimate): List<String> {
    val total = estimate.totalMs?.let { getString(R.string.diagnostics_ms_approx, it) } ?: getString(R.string.diagnostics_unknown)
    val parts =
        getString(
            R.string.diagnostics_estimate_parts,
            msOrUnknown(estimate.pollHalfMs),
            msOrUnknown(estimate.phonePathMs),
            msOrUnknown(estimate.networkOneWayMs),
        )
    return listOf(diagKv(R.string.diagnostics_latency_estimate, total), parts)
}

private fun Context.msOrUnknown(value: Double?): String =
    value?.let { getString(R.string.diagnostics_ms, it) } ?: getString(R.string.diagnostics_unknown)
