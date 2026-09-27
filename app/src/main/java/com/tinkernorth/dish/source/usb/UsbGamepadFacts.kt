// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.hardware.usb.UsbConstants
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry

private const val XINPUT_SUBCLASS = 0x5D
private const val XINPUT_PROTOCOL = 0x01
private const val XINPUT_AUX_PROTOCOL = 0x02
private const val XINPUT_AUDIO_PROTOCOL = 0x03
private const val XINPUT_SECURITY_SUBCLASS = 0xFD
private const val GIP_SUBCLASS = 0x47
private const val GIP_PROTOCOL = 0xD0

private const val HID_BOOT_SUBCLASS = 0x01
private const val HID_KEYBOARD_PROTOCOL = 0x01
private const val HID_MOUSE_PROTOCOL = 0x02

internal const val RANK_NONE = 0
internal const val RANK_HID_BOOT = 1
internal const val RANK_VENDOR_FALLBACK = 2
internal const val RANK_HID = 3
internal const val RANK_GIP = 4
internal const val RANK_XINPUT = 5

// How strongly an interface looks like the pad's input endpoint; RANK_NONE is never claimed.
internal fun gameInterfaceRank(facts: UsbInterfaceFacts): Int {
    if (facts.interfaceClass == UsbConstants.USB_CLASS_HID) return hidInterfaceRank(facts)
    if (facts.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) return RANK_NONE
    val sub = facts.interfaceSubclass
    val proto = facts.interfaceProtocol
    return when {
        sub == XINPUT_SUBCLASS && proto == XINPUT_PROTOCOL -> RANK_XINPUT
        sub == GIP_SUBCLASS && proto == GIP_PROTOCOL -> RANK_GIP
        sub == XINPUT_SUBCLASS && (proto == XINPUT_AUX_PROTOCOL || proto == XINPUT_AUDIO_PROTOCOL) -> RANK_NONE
        sub == XINPUT_SECURITY_SUBCLASS -> RANK_NONE
        else -> RANK_VENDOR_FALLBACK
    }
}

// A pad that also emulates a keyboard/mouse (the Steam Controller does, and lists them first) is
// never driven from its boot-protocol interfaces while it offers anything else.
private fun hidInterfaceRank(facts: UsbInterfaceFacts): Int {
    val isBootSubclass = facts.interfaceSubclass == HID_BOOT_SUBCLASS
    val isKeyboardOrMouse = facts.interfaceProtocol == HID_KEYBOARD_PROTOCOL || facts.interfaceProtocol == HID_MOUSE_PROTOCOL
    val bootHumanInterface = isBootSubclass && isKeyboardOrMouse
    return if (bootHumanInterface) RANK_HID_BOOT else RANK_HID
}

// The known-model table names the pad best, and a pad it names is never asked for its product
// string; a blank product string is not a name at all.
internal fun friendlyUsbName(
    knownModelName: String,
    deviceName: String,
    productName: () -> String?,
): String {
    if (knownModelName.isNotEmpty()) return knownModelName
    return productName()?.takeIf { it.isNotBlank() } ?: deviceName
}

// The framework InputDevice of this model that is really there right now, if any: a held
// placeholder, a needs-replug card or a device on its disconnect countdown does not count.
internal fun liveFrameworkFor(
    devices: Map<Int, PhysicalGamepadRegistry.Device>,
    vendorId: Int,
    productId: Int,
): Int? = devices.values.firstOrNull { isLiveFrameworkOf(it, vendorId, productId) }?.id

private fun isLiveFrameworkOf(
    device: PhysicalGamepadRegistry.Device,
    vendorId: Int,
    productId: Int,
): Boolean {
    val isFrameworkEntry = !device.isUsbSynthetic
    val isSettled = !device.transitioning && !device.needsReplug && !device.isDisconnecting
    val isSameModel = device.vendorId == vendorId && device.productId == productId
    return isFrameworkEntry && isSettled && isSameModel
}
