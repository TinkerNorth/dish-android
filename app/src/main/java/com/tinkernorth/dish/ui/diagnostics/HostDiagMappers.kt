// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.source.system.WifiSubnet
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID

internal fun hostDiags(
    world: DiagnosticsWorld,
    touchpadMode: (String) -> String,
): List<HostDiag> {
    val names = controllerNames(world)
    return world.summaries.map { summary ->
        val snapshot = world.satellites[summary.id]
        HostDiag(
            id = summary.id,
            label = summary.label,
            detail = summary.detail,
            kind = summary.kind,
            live = summary.live,
            btProfile = summary.btProfile,
            telemetry = snapshot?.telemetry,
            features = if (summary.kind == ConnectionKind.SATELLITE) world.hostFeatures[summary.id] else null,
            serverVersion = world.serverVersions[summary.id],
            slots = hostSlots(summary, snapshot, names, touchpadMode),
            history = world.links.history.connections[summary.id],
            satellite = snapshot,
            moonlight = world.links.moonlight[summary.id],
            bluetooth = world.links.btHosts[summary.id],
            sameSubnet = sameSubnet(world, summary),
        )
    }
}

internal fun controllerNames(world: DiagnosticsWorld): Map<String, String> =
    world.devices.values.associate { it.id.toString() to it.name } + (VIRTUAL_SLOT_ID to world.virtualName)

// Satellite and Moonlight summaries carry the host address in their detail line; the first
// dotted-quad in it is the address the phone has to reach.
internal fun sameSubnet(
    world: DiagnosticsWorld,
    summary: ConnectionSummary,
): Boolean? {
    if (summary.kind == ConnectionKind.BLUETOOTH) return null
    val wifi = world.radios.wifi ?: return null
    val hostIp = IPV4_IN_TEXT.find(summary.detail)?.value ?: return null
    return WifiSubnet.sameSubnet(wifi.ipv4, wifi.prefixLength, hostIp)
}

private val IPV4_IN_TEXT = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

private fun hostSlots(
    summary: ConnectionSummary,
    snapshot: SatelliteSnapshot?,
    names: Map<String, String>,
    touchpadMode: (String) -> String,
): List<HostSlotDiag> =
    summary.boundSlotIds
        .map { slotId ->
            val binding = snapshot?.slots?.get(slotId)
            HostSlotDiag(
                slotId = slotId,
                controllerName = names[slotId] ?: slotId,
                typeId = summary.satelliteControllerTypes[slotId],
                slotIndex = binding?.controllerIndex,
                touchpadMode = binding?.let { touchpadMode(slotId) },
                registered = binding?.registered,
                streaming = binding?.let { b -> snapshot?.telemetry?.let { streamingOn(it.activeBitmap, b.controllerIndex) } },
            )
        }.sortedBy { it.slotIndex ?: Int.MAX_VALUE }
