// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType

// On-screen glyph set for the virtual pad, one per emulated identity. The layout
// (stick/d-pad placement) splits the PlayStation family from the rest; the glyphs
// split every skin: Xbox360 carries Back/Start where Xbox carries View/Menu, and
// DualSense carries Create/Options where PlayStation carries the DS4 Share/Options.
enum class GamepadSkin {
    Xbox,
    Xbox360,
    PlayStation,
    DualSense,
    Switch,
    ;

    companion object {
        // Satellite catalog id → skin. The catalog's only Xbox is the emulated
        // Xbox 360 pad, so unknown ids fall back to its skin.
        fun forControllerType(type: Int): GamepadSkin =
            when (type) {
                CONTROLLER_TYPE_PLAYSTATION -> PlayStation
                CONTROLLER_TYPE_DUALSENSE -> DualSense
                CONTROLLER_TYPE_SWITCHPRO -> Switch
                else -> Xbox360
            }

        // Moonlight emulated type → skin, in the Moonlight id table (its ids overlap the
        // catalog's, so the two mappers never share a caller). AUTO must be resolved to a
        // concrete type first (MoonlightEmulatedType.resolve); an unresolved value lands
        // on the generic Xbox skin, matching the wire's own fallback.
        fun forMoonlightType(type: Int): GamepadSkin =
            when (type) {
                MoonlightEmulatedType.PLAYSTATION -> PlayStation
                MoonlightEmulatedType.NINTENDO -> Switch
                else -> Xbox
            }

        fun forBtProfile(profileName: String?): GamepadSkin =
            if (profileName == BluetoothGamepad.GamepadProfile.PLAYSTATION.profileName) PlayStation else Xbox

        fun fromName(name: String?): GamepadSkin = entries.firstOrNull { it.name == name } ?: Xbox
    }
}
