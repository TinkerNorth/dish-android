// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbPhase

internal fun wiredUsbPresentFor(
    device: PhysicalGamepadRegistry.Device,
    usbControllers: Collection<UsbController>,
): Boolean {
    if (device.transport != Transport.Bluetooth || device.isUsbSynthetic) return false
    return usbControllers.any { unrepresentedUsbTwinOf(device, it) }
}

private fun unrepresentedUsbTwinOf(
    device: PhysicalGamepadRegistry.Device,
    controller: UsbController,
): Boolean {
    if (controller.phase != UsbPhase.Routed || !controller.usbPresent || controller.syntheticId != null) return false
    if (controller.vendorId != device.vendorId) return false
    return controller.frameworkId == null || controller.frameworkId == device.id
}
