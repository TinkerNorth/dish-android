// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.BundleAck
import com.tinkernorth.dish.composer.BundleRtt
import com.tinkernorth.dish.composer.BundleSession
import com.tinkernorth.dish.composer.BundleSessionSlot
import com.tinkernorth.dish.composer.ConnectionKind

// The client's own view of each host's session, for every host kind.

private const val SENT_INPUT = "input"

internal fun bundleSessionOf(
    host: HostDiag,
    controllers: List<ControllerDiag>,
    typeSlugs: Map<Int, String>,
): BundleSession? =
    when (host.kind) {
        ConnectionKind.SATELLITE -> host.satellite?.let { satelliteSessionOf(it, controllers, typeSlugs) }
        ConnectionKind.MOONLIGHT -> host.moonlight?.let { moonlightSessionOf(it, controllers) }
        ConnectionKind.BLUETOOTH -> host.bluetooth?.let { bluetoothSessionOf(it, host.slots, controllers) }
    }

private fun satelliteSessionOf(
    snapshot: SatelliteSnapshot,
    controllers: List<ControllerDiag>,
    typeSlugs: Map<Int, String>,
): BundleSession {
    val slots =
        snapshot.slots
            .map { (slotId, binding) ->
                BundleSessionSlot(
                    index = binding.controllerIndex,
                    record = recordOf(slotId, controllers),
                    requestedType = typeName(binding.controllerType, typeSlugs),
                    appliedType = binding.lastAppliedType?.let { typeName(it, typeSlugs) },
                    backend = null,
                    result = binding.lastApplyResult,
                    touchpadMode = binding.lastAdvertisedTouchpadMode,
                )
            }.sortedBy { it.index }
    return BundleSession(
        slots = slots,
        ack = snapshot.telemetry?.let(::ackOf),
        rtt = snapshot.stats?.let { BundleRtt(it.rttP50Ms, it.rttP99Ms, it.rttSamples) },
        sent = snapshot.messages?.sent.orEmpty(),
        received = snapshot.messages?.received.orEmpty(),
    )
}

// An ack the satellite never enriched leaves the epoch at -1: there is nothing of it to report.
private fun ackOf(telemetry: SatelliteTelemetry): BundleAck? {
    val enriched = telemetry.epoch >= 0
    if (!enriched) return null
    return BundleAck(telemetry.vigemAvailable, telemetry.activeControllers, telemetry.epoch, telemetry.activeBitmap)
}

private fun moonlightSessionOf(
    snapshot: MoonlightSnapshot,
    controllers: List<ControllerDiag>,
): BundleSession {
    val slots =
        snapshot.pads.values
            .map { bareSlot(it.number, it.slotId, controllers) }
            .sortedBy { it.index }
    return BundleSession(
        slots = slots,
        ack = null,
        rtt = null,
        sent = mapOf(SENT_INPUT to snapshot.reportsByNumber.values.sum()),
        received = emptyMap(),
    )
}

private fun bluetoothSessionOf(
    snapshot: BtHostSnapshot,
    hostSlots: List<HostSlotDiag>,
    controllers: List<ControllerDiag>,
): BundleSession {
    val slots = hostSlots.mapNotNull { slot -> slot.slotIndex?.let { bareSlot(it, slot.slotId, controllers) } }
    return BundleSession(slots = slots, ack = null, rtt = null, sent = mapOf(SENT_INPUT to snapshot.reportsSent), received = emptyMap())
}

private fun bareSlot(
    index: Int,
    slotId: String,
    controllers: List<ControllerDiag>,
): BundleSessionSlot = BundleSessionSlot(index, recordOf(slotId, controllers), null, null, null, null, null)

private fun recordOf(
    slotId: String,
    controllers: List<ControllerDiag>,
): String? =
    controllers
        .firstOrNull { it.slotId == slotId }
        ?.facts
        ?.record
        ?.id

// The catalog's slug for a type, or its number where this host's catalog was never read.
private fun typeName(
    type: Int,
    typeSlugs: Map<Int, String>,
): String = typeSlugs[type] ?: type.toString()
