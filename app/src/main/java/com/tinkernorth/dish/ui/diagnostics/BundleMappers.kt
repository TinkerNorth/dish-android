// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.BundleAudio
import com.tinkernorth.dish.composer.BundleCapture
import com.tinkernorth.dish.composer.BundleController
import com.tinkernorth.dish.composer.BundleFeedback
import com.tinkernorth.dish.composer.BundleGrants
import com.tinkernorth.dish.composer.BundleHost
import com.tinkernorth.dish.composer.BundleInputs
import com.tinkernorth.dish.composer.BundleOutput
import com.tinkernorth.dish.composer.HostSnapshot
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.audio.PadAudioReason
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry

// Every controller of the report, the on-screen pad on its own path, every host as an alias, and
// the values that must not leave: each host's label, id and detail, and the phone's Wi-Fi address.
internal fun bundleInputsOf(
    world: DiagnosticsWorld,
    controllers: List<ControllerDiag>,
    hosts: List<HostDiag>,
    captures: Map<String, BundleCapture>,
    log: List<DiagnosticsLogEntry>,
    envelope: BundleEnvelope,
    extras: BundleExtras = BundleExtras(),
): BundleInputs {
    val riding = capturesRiding(captures, extras.reportSlotId)
    return BundleInputs(
        createdAtMs = envelope.createdAtMs,
        app = envelope.app,
        device = envelope.device,
        controllerDb = world.db.status,
        controllers = controllers.map { bundleControllerOf(it, world, riding, extras) },
        hosts = hosts.map { bundleHostOf(it, controllers, extras) },
        log = log,
        logcat = envelope.logcat,
        forbidden = forbiddenValuesOf(world, hosts),
        report = extras.report,
    )
}

internal fun capturesRiding(
    captures: Map<String, BundleCapture>,
    slotId: String?,
): Map<String, BundleCapture> = captures.filterKeys { it == slotId }

private fun bundleControllerOf(
    diag: ControllerDiag,
    world: DiagnosticsWorld,
    captures: Map<String, BundleCapture>,
    extras: BundleExtras,
): BundleController {
    val facts = diag.facts
    val deviceId = diag.slotId.toIntOrNull()
    val device = deviceId?.let(world.devices::get)
    val frames = deviceId?.let(world.pads.reportCounts::get)
    val state = deviceId?.let(extras.deviceStates::get)
    val sources = deviceId?.let(extras.inputSources::get)
    val linkType = deviceId?.let(world.pads.btLinkTypes::get)
    val serviceUuids = deviceId?.let(world.pads.btServiceUuids::get).orEmpty()
    return BundleController(
        record = facts?.record?.id,
        verification = facts?.record?.verification,
        transport = if (diag.isVirtual) null else dbTransportOf(diag.transport),
        path = bundlePathOf(diag),
        hardware = facts?.hardware,
        firmware = facts?.direct?.firmware,
        expectMismatch = facts?.expectMismatches.orEmpty(),
        rateHz = diag.pollRateHz,
        lastFeedback = world.links.feedback[diag.slotId]?.let { BundleFeedback(it.lastKind.name.lowercase(), it.atMs, it.count) },
        lastWrite = facts?.lastWrite,
        audio =
            facts?.audio?.let {
                BundleAudio(
                    channels = it.route.playbackChannels,
                    refused = it.reason != PadAudioReason.ROUTED,
                )
            },
        outcomes = world.pads.outcomes[diag.slotId].orEmpty(),
        capture = captures[diag.slotId],
        input = bundleInputOf(diag, frames, state),
        output = bundleOutputOf(diag.slotId, facts, world, extras),
        standard = bundleStandardOf(diag, sources, device),
        bluetooth = bundleBluetoothOf(diag, linkType, serviceUuids),
    )
}

private fun bundleOutputOf(
    slotId: String,
    facts: PadFacts?,
    world: DiagnosticsWorld,
    extras: BundleExtras,
): BundleOutput =
    BundleOutput(
        target = feedbackTargetWire(feedbackTarget(slotId)),
        lanes = bundleLanesOf(extras.lanes[slotId].orEmpty()),
        lastWrite = facts?.lastWrite,
        speaker = bundleVoiceOf(slotId, PlayoutLane.SPEAKER, world.audio.speakerVoices, extras.refusals),
        haptics = bundleVoiceOf(slotId, PlayoutLane.HAPTICS, world.audio.speakerVoices, extras.refusals),
    )

private fun bundleHostOf(
    host: HostDiag,
    controllers: List<ControllerDiag>,
    extras: BundleExtras,
): BundleHost =
    BundleHost(
        kind = host.kind.name.lowercase(),
        version = host.serverVersion,
        protocol = host.features?.protocolVersion?.takeIf { it > 0 } ?: host.satellite?.facts?.protocolVersion,
        link = linkName(host.live),
        grants =
            host.features?.let {
                BundleGrants(mic = it.controllerMic, speaker = it.controllerSpeaker, hapticAudio = it.controllerHapticAudio)
            },
        session = bundleSessionOf(host, controllers, extras.typeSlugs[host.id].orEmpty()),
        snapshot = extras.snapshots[host.id] ?: HostSnapshot.NONE,
        ownNames = ownNamesOf(host, extras.hostAddresses[host.id]),
    )

// What a host is called and where it is: its label and the address the app reaches it at.
internal fun ownNamesOf(
    host: HostDiag,
    address: String?,
): Set<String> = listOfNotNull(host.label, address).filter { it.isNotBlank() }.toSet()

internal fun linkName(state: LinkState): String =
    when (state) {
        LinkState.Connected -> "active"
        LinkState.Unstable -> "unstable"
        LinkState.Connecting -> "connecting"
        LinkState.Ready -> "ready"
        LinkState.Saved -> "saved"
        LinkState.Found -> "found"
        LinkState.Stale -> "stale"
    }

private fun forbiddenValuesOf(
    world: DiagnosticsWorld,
    hosts: List<HostDiag>,
): Set<String> {
    val hostValues = hosts.flatMap { listOf(it.id, it.label, it.detail) }
    val summaryValues = world.summaries.flatMap { listOf(it.id, it.label, it.detail) }
    val wifi = listOfNotNull(world.radios.wifi?.ipv4)
    return (hostValues + summaryValues + wifi).filter { it.isNotBlank() }.toSet()
}
