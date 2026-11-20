// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.BundleBluetooth
import com.tinkernorth.dish.composer.BundleInput
import com.tinkernorth.dish.composer.BundleInputState
import com.tinkernorth.dish.composer.BundleStandard
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType

// Which path reads a controller, and what that path can say about it. A field a path cannot know
// is null rather than guessed.

private const val PATH_DIRECT = "direct"
private const val PATH_STANDARD = "standard"
private const val PATH_BLUETOOTH = "bluetooth"
private const val PATH_VIRTUAL = "virtual"

private const val PROFILE_HID = "hid"
private const val PROFILE_BLE = "ble"
private const val LAYER_LE = "le"

internal fun bundlePathOf(diag: ControllerDiag): String {
    val isOverBluetooth = diag.transport == Transport.Bluetooth
    return when {
        diag.isVirtual -> PATH_VIRTUAL
        isOverBluetooth -> PATH_BLUETOOTH
        diag.isUsbSynthetic -> PATH_DIRECT
        else -> PATH_STANDARD
    }
}

// A pad over Bluetooth is the phone's HID over the link it bonded on, or the app's own BLE client
// (a synthetic pad), which only speaks LE.
internal fun bundleBluetoothOf(
    diag: ControllerDiag,
    linkType: BluetoothLinkType?,
    serviceUuids: List<String> = emptyList(),
): BundleBluetooth? {
    val isOverBluetooth = diag.transport == Transport.Bluetooth
    if (!isOverBluetooth) return null
    if (diag.isUsbSynthetic) return BundleBluetooth(PROFILE_BLE, LAYER_LE)
    return BundleBluetooth(PROFILE_HID, linkType?.let { transportLayerOf(it, serviceUuids) })
}

private const val LAYER_CLASSIC = "classic"
private const val UUID_HID_CLASSIC = "00001124-0000-1000-8000-00805f9b34fb"
private const val UUID_HID_OVER_GATT = "00001812-0000-1000-8000-00805f9b34fb"

// A dual-mode bond does not say which radio the pad is on now, so its cached service UUIDs decide:
// the HID profile alone is classic, HID over GATT alone is le, both or neither is no answer.
internal fun transportLayerOf(
    linkType: BluetoothLinkType,
    serviceUuids: List<String>,
): String? =
    when (linkType) {
        BluetoothLinkType.CLASSIC -> LAYER_CLASSIC
        BluetoothLinkType.LOW_ENERGY -> LAYER_LE
        BluetoothLinkType.DUAL -> dualLayerOf(serviceUuids)
        BluetoothLinkType.UNKNOWN -> null
    }

private fun dualLayerOf(serviceUuids: List<String>): String? {
    val lower = serviceUuids.map { it.lowercase() }
    val hasClassic = UUID_HID_CLASSIC in lower
    val hasGatt = UUID_HID_OVER_GATT in lower
    return when {
        hasClassic && !hasGatt -> LAYER_CLASSIC
        hasGatt && !hasClassic -> LAYER_LE
        else -> null
    }
}

// A pad the phone's input framework reads: the sources it declared, and whether the framework
// gave it a vibrator and lights.
internal fun bundleStandardOf(
    diag: ControllerDiag,
    sources: Int?,
    device: PhysicalGamepadRegistry.Device?,
): BundleStandard? {
    val isReadByThePhone = !diag.isVirtual && !diag.isUsbSynthetic
    if (!isReadByThePhone) return null
    return BundleStandard(
        sources = sources?.let(::sourceNamesOf).orEmpty(),
        vibrator = device?.hasRumble,
        lights = device?.hasLightbar,
    )
}

private fun sourceNamesOf(sources: Int): List<String> =
    InputSourceKind.entries
        .filter { (sources and it.mask) == it.mask }
        .map { it.name.lowercase() }

internal fun bundleInputOf(
    diag: ControllerDiag,
    frames: Long?,
    snapshot: InputSnapshot?,
): BundleInput =
    BundleInput(
        frames = frames,
        rateHz = diag.pollRateHz.takeIf { it > 0 },
        last = snapshot?.let(::inputStateOf),
    )

private fun inputStateOf(snapshot: InputSnapshot): BundleInputState =
    BundleInputState(
        buttons = snapshot.buttons,
        lx = snapshot.lx,
        ly = snapshot.ly,
        rx = snapshot.rx,
        ry = snapshot.ry,
        lt = snapshot.lt,
        rt = snapshot.rt,
        ext = snapshot.extButtons,
    )
