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
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioReason
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry

// Every physical pad of the report (the on-screen pad is the phone, not a controller), every host
// as an alias, and the values that must not leave: each host's label, id and detail, and the
// phone's Wi-Fi address.
internal fun bundleInputsOf(
    world: DiagnosticsWorld,
    controllers: List<ControllerDiag>,
    hosts: List<HostDiag>,
    captures: Map<String, BundleCapture>,
    log: List<DiagnosticsLogEntry>,
    envelope: BundleEnvelope,
): BundleInputs =
    BundleInputs(
        createdAtMs = envelope.createdAtMs,
        app = envelope.app,
        device = envelope.device,
        controllerDb = world.db.status,
        controllers = controllers.filter { !it.isVirtual }.map { bundleControllerOf(it, world, captures) },
        hosts = hosts.map(::bundleHostOf),
        log = log,
        logcat = envelope.logcat,
        forbidden = forbiddenValuesOf(world, hosts),
    )

internal fun bundlePathOf(diag: ControllerDiag): String =
    when {
        diag.isUsbSynthetic -> PATH_DIRECT
        diag.transport == Transport.Bluetooth -> PATH_BLUETOOTH
        else -> PATH_FRAMEWORK
    }

private const val PATH_DIRECT = "direct"
private const val PATH_BLUETOOTH = "bluetooth"
private const val PATH_FRAMEWORK = "framework"

private fun bundleControllerOf(
    diag: ControllerDiag,
    world: DiagnosticsWorld,
    captures: Map<String, BundleCapture>,
): BundleController {
    val facts = diag.facts
    return BundleController(
        record = facts?.record?.id,
        verification = facts?.record?.verification,
        transport = dbTransportOf(diag.transport),
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
    )
}

private fun bundleHostOf(host: HostDiag): BundleHost =
    BundleHost(
        kind = host.kind.name.lowercase(),
        version = host.serverVersion,
        protocol = host.features?.protocolVersion?.takeIf { it > 0 } ?: host.satellite?.facts?.protocolVersion,
        link = linkName(host.live),
        grants =
            host.features?.let {
                BundleGrants(mic = it.controllerMic, speaker = it.controllerSpeaker, hapticAudio = it.controllerHapticAudio)
            },
    )

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
