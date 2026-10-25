// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

// QUIRK_* bits must match gamepad_input.h; pushed to native per device.
internal const val QUIRK_NONE = 0
internal const val QUIRK_SWAP_AB = 0x01
internal const val QUIRK_SWAP_XY = 0x02
internal const val QUIRK_SWITCH_LAYOUT = 0x04
internal const val QUIRK_N64_HID_LAYOUT = 0x08
internal const val QUIRK_N64_DRIVER_LAYOUT = 0x10
internal const val QUIRK_EIGHTBITDO_DINPUT = 0x20
internal const val QUIRK_XPAD_TRIGGER_AXES = 0x40

private const val VENDOR_MICROSOFT = 0x045E
private const val VENDOR_NINTENDO = 0x057E
private const val VENDOR_PDP = 0x0E6F
private const val VENDOR_8BITDO = 0x2DC8
private const val PRODUCT_NINTENDO_N64 = 0x2019

// PDP's wired Switch pads (SDL's SwitchInputOnlyController set): plain HID devices whose buttons
// arrive in the Switch usage order, so Generic.kl shifts the whole row. 0x0186 is excluded on
// purpose: it speaks the Switch Pro protocol and its USB port is charge-only.
private val PDP_SWITCH_LAYOUT_PRODUCTS = setOf(0x0180, 0x0181, 0x0184, 0x0185, 0x0187)

// 8BitDo's D-input identities: the M30 over USB and over Bluetooth, and the two USB wireless
// adapters. Heart arrives as BTN_C, and the analog Accelerator and Brake only echo the digital
// L and R.
private val EIGHTBITDO_DINPUT_PRODUCTS = setOf(0x5006, 0x0651, 0x3100, 0x3105)

// Which joystick axes a device reports (InputDevice.getMotionRange), as the quirk rules read them.
internal data class ReportedAxes(
    val z: Boolean = false,
    val rz: Boolean = false,
    val rx: Boolean = false,
    val ry: Boolean = false,
    val lTrigger: Boolean = false,
    val rTrigger: Boolean = false,
    val brake: Boolean = false,
    val gas: Boolean = false,
) {
    // The kernel's xpad driver with no key layout file: Generic.kl leaves the triggers on Z and RZ
    // and the right stick on RX and RY, and names no trigger axis at all.
    val lookLikeXpadWithoutALayout: Boolean
        get() = z && rz && rx && ry && !lTrigger && !rTrigger && !brake && !gas
}

// Nintendo's A/B and X/Y sit opposite Xbox; remap by position, like SDL/Steam/Moonlight.
internal fun resolveGamepadQuirk(
    vendorId: Int,
    productId: Int,
    axes: ReportedAxes,
): Int =
    when {
        vendorId == VENDOR_NINTENDO && productId == PRODUCT_NINTENDO_N64 ->
            if (axes.rx) QUIRK_N64_HID_LAYOUT else QUIRK_N64_DRIVER_LAYOUT
        vendorId == VENDOR_NINTENDO -> QUIRK_SWAP_AB or QUIRK_SWAP_XY
        vendorId == VENDOR_PDP && productId in PDP_SWITCH_LAYOUT_PRODUCTS -> QUIRK_SWITCH_LAYOUT
        vendorId == VENDOR_8BITDO && productId in EIGHTBITDO_DINPUT_PRODUCTS -> QUIRK_EIGHTBITDO_DINPUT
        vendorId == VENDOR_MICROSOFT && axes.lookLikeXpadWithoutALayout -> QUIRK_XPAD_TRIGGER_AXES
        else -> QUIRK_NONE
    }
