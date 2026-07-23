// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO

// On-screen glyph set for the virtual pad. The layout (stick/d-pad placement) splits
// PlayStation from the rest; the glyphs split all three.
enum class GamepadSkin {
    Xbox,
    PlayStation,
    Switch,
    ;

    companion object {
        // Catalog id → skin. DualSense reuses the DualShock art; unknown ids fall back
        // to Xbox, matching the picker's default.
        fun forControllerType(type: Int): GamepadSkin =
            when (type) {
                CONTROLLER_TYPE_PLAYSTATION, CONTROLLER_TYPE_DUALSENSE -> PlayStation
                CONTROLLER_TYPE_SWITCHPRO -> Switch
                else -> Xbox
            }

        fun fromName(name: String?): GamepadSkin = entries.firstOrNull { it.name == name } ?: Xbox
    }
}
