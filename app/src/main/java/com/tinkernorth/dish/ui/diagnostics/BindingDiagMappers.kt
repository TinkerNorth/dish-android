// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID

internal fun bindingDiag(
    slotId: String,
    world: DiagnosticsWorld,
    touchpadMode: (String) -> String,
): BindingDiag? {
    val host = boundHostDiag(slotId, world, touchpadMode) ?: return null
    val isVirtual = slotId == VIRTUAL_SLOT_ID
    val device = slotId.toIntOrNull()?.let { world.devices[it] }
    val snapshot = world.satellites[host.connectionId]
    val binding = snapshot?.slots?.get(slotId)
    val index = binding?.controllerIndex
    val rates = world.rates[slotId]
    return BindingDiag(
        slotId = slotId,
        controllerName = if (isVirtual) world.virtualName else device?.name ?: slotId,
        isVirtual = isVirtual,
        host = host,
        boundSinceMs = world.links.history.boundSinceMs[slotId],
        declaredCaps = binding?.lastAdvertisedCaps,
        declaredTouchpadMode = binding?.lastAdvertisedTouchpadMode,
        applyResult = binding?.lastApplyResult,
        motionBackend = world.links.motionBackend[host.connectionId to slotId],
        caps = world.caps[slotId],
        packetsSent = packetsSent(slotId, host, snapshot, index, world),
        motionSent = index?.let { snapshot.slotMotion[it] },
        touchpadMode = touchpadMode(slotId),
        batterySource = batterySource(device?.transport, isVirtual),
        rumbleTarget = feedbackTarget(slotId),
        feedback = world.links.feedback[slotId],
        micState = micState(slotId, world),
        speakerPlaying =
            world.audio.speakerVoices.values
                .any { it.slotId == slotId },
        speakerDropped = world.audio.speakerDrops[slotId] ?: 0L,
        latency =
            LatencyEstimatePolicy.estimate(
                pollRateHz = rates?.controllerHz?.takeIf { it > 0 } ?: device?.pollRateHz ?: 0,
                phonePathMs = device?.let { world.pads.deviceLatency[it.id]?.stage1P50Ms },
                rttMs = rttFor(host, snapshot, world),
            ),
    )
}

private fun packetsSent(
    slotId: String,
    host: BoundHostDiag,
    snapshot: SatelliteSnapshot?,
    index: Int?,
    world: DiagnosticsWorld,
): Long? =
    when (host.kind) {
        ConnectionKind.SATELLITE -> index?.let { snapshot?.slotSends?.get(it) }
        ConnectionKind.MOONLIGHT -> {
            val ml = world.links.moonlight[host.connectionId]
            ml
                ?.pads
                ?.values
                ?.firstOrNull { it.slotId == slotId }
                ?.let { pad -> ml.reportsByNumber[pad.number] }
        }
        ConnectionKind.BLUETOOTH -> world.links.btHosts[host.connectionId]?.reportsSent
    }

private fun rttFor(
    host: BoundHostDiag,
    snapshot: SatelliteSnapshot?,
    world: DiagnosticsWorld,
): Double? =
    when (host.kind) {
        ConnectionKind.SATELLITE -> snapshot?.stats?.rttP50Ms
        ConnectionKind.MOONLIGHT ->
            world.links.moonlight[host.connectionId]
                ?.rttMs
                ?.toDouble()
        ConnectionKind.BLUETOOTH -> null
    }

internal fun batterySource(
    transport: Transport?,
    isVirtual: Boolean,
): BatterySource =
    when {
        isVirtual -> BatterySource.PHONE
        transport == Transport.Bluetooth -> BatterySource.LOWEST_OF_BOTH
        else -> BatterySource.PHONE
    }

internal fun feedbackTarget(slotId: String): FeedbackTargetKind {
    if (slotId == VIRTUAL_SLOT_ID) return FeedbackTargetKind.PHONE
    val id = slotId.toIntOrNull() ?: return FeedbackTargetKind.NONE
    return if (id < 0) FeedbackTargetKind.PAD_DIRECT else FeedbackTargetKind.PAD_FRAMEWORK
}

internal fun micState(
    slotId: String,
    world: DiagnosticsWorld,
): MicSlotState {
    val plan = world.audio.micPlan
    return when {
        plan.delivering.any { it.slotId == slotId } -> MicSlotState.CAPTURING
        plan.armed.any { it.slotId == slotId } -> MicSlotState.ARMED_MUTED
        else -> MicSlotState.OFF
    }
}

internal fun declaredFeatures(caps: Int): List<Feature> =
    buildList {
        if (caps and CAP_ANALOG_TRIGGERS != 0) add(Feature.ANALOG_TRIGGERS)
        if (caps and CAP_RUMBLE != 0) add(Feature.RUMBLE)
        if (caps and CAP_MOTION != 0) add(Feature.MOTION)
        if (caps and CAP_LIGHTBAR != 0) add(Feature.LIGHTBAR)
        if (caps and CAP_TRIGGER_EFFECTS != 0) add(Feature.TRIGGER_EFFECTS)
        if (caps and CAP_PLAYER_LEDS != 0) add(Feature.PLAYER_LEDS)
        if (caps and CAP_MIC != 0) add(Feature.MIC)
        if (caps and CAP_SPEAKER != 0) add(Feature.SPEAKER)
    }

private const val CAP_ANALOG_TRIGGERS = 0x0001
private const val CAP_RUMBLE = 0x0002
private const val CAP_MOTION = 0x0004
private const val CAP_LIGHTBAR = 0x0008
private const val CAP_TRIGGER_EFFECTS = 0x0010
private const val CAP_PLAYER_LEDS = 0x0020
private const val CAP_MIC = 0x0040
private const val CAP_SPEAKER = 0x0080
