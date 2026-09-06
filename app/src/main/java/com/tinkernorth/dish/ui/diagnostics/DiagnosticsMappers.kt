// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.ui.main.BatteryUi
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import com.tinkernorth.dish.ui.main.routedTwinIdsHiddenBySynthetics

private val FUNCTION_FEATURES =
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
    )

internal fun boundHostDiag(
    slotId: String,
    world: DiagnosticsWorld,
    touchpadMode: (String) -> String,
): BoundHostDiag? {
    val connId = world.bindings[slotId] ?: return null
    val summary = world.summariesById[connId] ?: return null
    val base =
        BoundHostDiag(
            connectionId = connId,
            label = summary.label,
            kind = summary.kind,
            live = summary.live,
            typeId = summary.satelliteControllerTypes[slotId],
            btProfile = summary.btProfile,
        )
    val snapshot = world.satellites[connId]
    val binding = snapshot?.slots?.get(slotId)
    if (summary.kind != ConnectionKind.SATELLITE || binding == null) return base
    return base.copy(
        slotIndex = binding.controllerIndex,
        touchpadMode = touchpadMode(slotId),
        registered = binding.registered,
        streaming = snapshot.telemetry?.let { streamingOn(it.activeBitmap, binding.controllerIndex) },
    )
}

internal fun streamingOn(
    activeBitmap: Int,
    controllerIndex: Int,
): Boolean = activeBitmap >= 0 && (activeBitmap and (1 shl controllerIndex)) != 0

internal fun controllerDiags(
    world: DiagnosticsWorld,
    touchpadMode: (String) -> String,
): List<ControllerDiag> {
    val hidden = routedTwinIdsHiddenBySynthetics(world.devices.values)
    val physical =
        world.devices.values
            .filter { it.isUsbSynthetic || it.id !in hidden }
            .sortedBy { it.name }
            .map { device -> physicalDiag(device, world, touchpadMode) }
    return listOf(virtualDiag(world, touchpadMode)) + physical
}

private fun virtualDiag(
    world: DiagnosticsWorld,
    touchpadMode: (String) -> String,
): ControllerDiag =
    ControllerDiag(
        slotId = VIRTUAL_SLOT_ID,
        name = world.virtualName,
        isVirtual = true,
        transport = null,
        isUsbSynthetic = false,
        hasGyro = false,
        pollRateHz = 0,
        gyroHz = world.rates[VIRTUAL_SLOT_ID]?.gyroHz ?: 0,
        state = ControllerDiagState.CONNECTED,
        battery = batteryUi(world, VIRTUAL_SLOT_ID),
        host = boundHostDiag(VIRTUAL_SLOT_ID, world, touchpadMode),
        functions = functionsOf(world.caps[VIRTUAL_SLOT_ID]),
    )

private fun physicalDiag(
    device: PhysicalGamepadRegistry.Device,
    world: DiagnosticsWorld,
    touchpadMode: (String) -> String,
): ControllerDiag {
    val slotId = device.id.toString()
    val rates = world.rates[slotId]
    val measuredHz = rates?.controllerHz ?: 0
    return ControllerDiag(
        slotId = slotId,
        name = device.name,
        isVirtual = false,
        transport = device.transport,
        isUsbSynthetic = device.isUsbSynthetic,
        hasGyro = device.hasGyro,
        pollRateHz = if (measuredHz > 0) measuredHz else device.pollRateHz,
        gyroHz = rates?.gyroHz ?: 0,
        state = stateOf(device),
        battery = batteryUi(world, slotId),
        host = boundHostDiag(slotId, world, touchpadMode),
        functions = functionsOf(world.caps[slotId]),
    )
}

private fun stateOf(device: PhysicalGamepadRegistry.Device): ControllerDiagState =
    when {
        device.needsReplug -> ControllerDiagState.NEEDS_REPLUG
        device.restoreStuck || device.transitioning -> ControllerDiagState.TRANSITIONING
        device.isDisconnecting -> ControllerDiagState.DISCONNECTING
        else -> ControllerDiagState.CONNECTED
    }

private fun batteryUi(
    world: DiagnosticsWorld,
    slotId: String,
): BatteryUi? = world.batteries[slotId]?.let { BatteryUi.fromWire(it.level, it.status) }

internal fun functionsOf(caps: SlotCapabilities?): List<Feature> {
    caps ?: return emptyList()
    return FUNCTION_FEATURES.filter { caps.inputOk(it) }
}
