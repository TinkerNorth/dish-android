// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

// QUIRK_* bits must match gamepad_input.h; pushed to native per device.
internal const val QUIRK_NONE = 0
internal const val QUIRK_SWAP_AB = 0x01
internal const val QUIRK_SWAP_XY = 0x02

private const val VENDOR_NINTENDO = 0x057E

// Nintendo's A/B and X/Y sit opposite Xbox; remap by position, like SDL/Steam/Moonlight.
internal fun resolveGamepadQuirk(vendorId: Int): Int {
    if (vendorId != VENDOR_NINTENDO) return QUIRK_NONE
    return QUIRK_SWAP_AB or QUIRK_SWAP_XY
}
