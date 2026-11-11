// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.app.PendingIntent
import android.hardware.usb.UsbConstants
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry

private const val XINPUT_SUBCLASS = 0x5D
private const val XINPUT_PROTOCOL = 0x01
private const val XINPUT_AUX_PROTOCOL = 0x02
private const val XINPUT_AUDIO_PROTOCOL = 0x03
private const val XINPUT_SECURITY_SUBCLASS = 0xFD
private const val GIP_SUBCLASS = 0x47
private const val GIP_PROTOCOL = 0xD0

private const val VALVE_VENDOR_ID = 0x28DE
private const val TRITON_PROTEUS_DONGLE_PRODUCT_ID = 0x1304
private const val TRITON_NEREID_DONGLE_PRODUCT_ID = 0x1305
private val TRITON_DONGLE_CONTROLLER_INTERFACES = 2..5

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

// An interface that carries an interrupt-IN endpoint, with the number the device gave it.
internal data class UsbInterfaceCandidate(
    val number: Int,
    val facts: UsbInterfaceFacts,
)

// The index of the best-ranked offered candidate, the earliest when ranks tie; null when none is claimable.
internal fun pickGameInterface(
    vendorId: Int,
    productId: Int,
    candidates: List<UsbInterfaceCandidate>,
): Int? {
    val offered = candidates.withIndex().filter { isOfferedInterface(vendorId, productId, it.value.number) }
    val ranked = offered.filter { gameInterfaceRank(it.value.facts) > RANK_NONE }
    return ranked.maxByOrNull { gameInterfaceRank(it.value.facts) }?.index
}

private fun isTritonDongle(
    vendorId: Int,
    productId: Int,
): Boolean {
    val isValve = vendorId == VALVE_VENDOR_ID
    val isProteus = productId == TRITON_PROTEUS_DONGLE_PRODUCT_ID
    val isNereid = productId == TRITON_NEREID_DONGLE_PRODUCT_ID
    return isValve && (isProteus || isNereid)
}

// SDL and Steam take only interfaces 2 to 5 of the 2026 Steam Controller's dongles as controllers.
private fun isOfferedInterface(
    vendorId: Int,
    productId: Int,
    number: Int,
): Boolean = !isTritonDongle(vendorId, productId) || number in TRITON_DONGLE_CONTROLLER_INTERFACES

// A pad that also emulates a keyboard/mouse (the Steam Controller does, and lists them first) is
// never driven from its boot-protocol interfaces while it offers anything else.
private fun hidInterfaceRank(facts: UsbInterfaceFacts): Int {
    val isBootSubclass = facts.interfaceSubclass == HID_BOOT_SUBCLASS
    val isKeyboardOrMouse = facts.interfaceProtocol == HID_KEYBOARD_PROTOCOL || facts.interfaceProtocol == HID_MOUSE_PROTOCOL
    val bootHumanInterface = isBootSubclass && isKeyboardOrMouse
    return if (bootHumanInterface) RANK_HID_BOOT else RANK_HID
}

// The known-model table names the pad best, and a pad it names is never asked for its product
// string or its device node; a blank product string is not a name at all.
internal fun friendlyUsbName(
    knownModelName: String,
    deviceName: () -> String,
    productName: () -> String?,
): String {
    if (knownModelName.isNotEmpty()) return knownModelName
    return productName()?.takeIf { it.isNotBlank() } ?: deviceName()
}

// UsbManager fills the device and the grant into the permission broadcast, which needs a mutable
// PendingIntent from 31; below 31 the flag does not exist.
internal fun usbPermissionIntentFlags(sdkInt: Int): Int {
    if (atLeast(Build.VERSION_CODES.S, sdkInt)) return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    return PendingIntent.FLAG_UPDATE_CURRENT
}

// The annotation is what lets lint read a caller-supplied API level as the gate FLAG_MUTABLE
// needs; a bare `sdkInt >= api` comparison it cannot see through.
@ChecksSdkIntAtLeast(parameter = 0)
private fun atLeast(
    api: Int,
    sdkInt: Int,
): Boolean = sdkInt >= api

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
