// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.hardware.usb.UsbDevice
import com.tinkernorth.dish.core.input.vidPidKey

private const val FIRST_UNIT = 0

// One physical USB device: its model's vidPidKey, and the unit that tells it from the model's
// other devices.
data class UsbUnitKey(
    val model: Int,
    val unit: Int,
)

internal fun freeUnitKey(
    model: Int,
    tracked: Collection<UsbUnitKey>,
): UsbUnitKey {
    val held = tracked.filter { it.model == model }.map { it.unit }.toSet()
    val unit = (FIRST_UNIT..held.size).first { it !in held }
    return UsbUnitKey(model, unit)
}

// The device node names one physical device and stays the same while it stays plugged in.
internal fun trackedKeyOf(
    device: UsbDevice,
    tracked: Map<UsbUnitKey, UsbDevice>,
): UsbUnitKey? {
    val model = vidPidKey(device.vendorId, device.productId)
    val ofModel = tracked.filterKeys { it.model == model }
    return ofModel.entries.firstOrNull { it.value.deviceName == device.deviceName }?.key
}

internal fun unitKeyOf(
    device: UsbDevice,
    tracked: Map<UsbUnitKey, UsbDevice>,
): UsbUnitKey = trackedKeyOf(device, tracked) ?: freeUnitKey(vidPidKey(device.vendorId, device.productId), tracked.keys)

internal fun firstOfEachModel(byUnit: Map<UsbUnitKey, UsbController>): Map<Int, UsbController> =
    byUnit.entries
        .sortedBy { it.key.unit }
        .distinctBy { it.key.model }
        .associate { it.key.model to it.value }
