// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

// QUIRK_* bits must match gamepad_input.h; pushed to native per device.
internal const val QUIRK_NONE = 0
internal const val QUIRK_SWAP_AB = 0x01
internal const val QUIRK_SWAP_XY = 0x02
internal const val QUIRK_SWITCH_LAYOUT = 0x04
internal const val QUIRK_N64_HID_LAYOUT = 0x08
internal const val QUIRK_N64_DRIVER_LAYOUT = 0x10

private const val VENDOR_NINTENDO = 0x057E
private const val VENDOR_PDP = 0x0E6F
private const val PRODUCT_NINTENDO_N64 = 0x2019

// PDP's wired Switch pads (SDL's SwitchInputOnlyController set): plain HID devices whose buttons
// arrive in the Switch usage order, so Generic.kl shifts the whole row. 0x0186 is excluded on
// purpose: it speaks the Switch Pro protocol and its USB port is charge-only.
private val PDP_SWITCH_LAYOUT_PRODUCTS = setOf(0x0180, 0x0181, 0x0184, 0x0185, 0x0187)

// Nintendo's A/B and X/Y sit opposite Xbox; remap by position, like SDL/Steam/Moonlight.
internal fun resolveGamepadQuirk(
    vendorId: Int,
    productId: Int,
    reportsRxAxis: Boolean,
): Int =
    when {
        vendorId == VENDOR_NINTENDO && productId == PRODUCT_NINTENDO_N64 ->
            if (reportsRxAxis) QUIRK_N64_HID_LAYOUT else QUIRK_N64_DRIVER_LAYOUT
        vendorId == VENDOR_NINTENDO -> QUIRK_SWAP_AB or QUIRK_SWAP_XY
        vendorId == VENDOR_PDP && productId in PDP_SWITCH_LAYOUT_PRODUCTS -> QUIRK_SWITCH_LAYOUT
        else -> QUIRK_NONE
    }
