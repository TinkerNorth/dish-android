// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.core.input.GamepadProfile
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION

// On-screen glyph set for the virtual pad, one per emulated identity. The layout
// (stick/d-pad placement) splits the PlayStation family from the rest; the glyphs
// split every skin: Xbox360 carries Back/Start where Xbox carries View/Menu, and
// DualSense carries Create/Options where PlayStation carries the DS4 Share/Options.
enum class GamepadSkin(
    val hasLightbar: Boolean = false,
    // The mic-mute button under the PS button, DualSense only: the DS4 v2 has no such button, and
    // only the DualSense identity maps WBUTTON_MIC_MUTE into the emulated pad's input report, so
    // offering it anywhere else would be a control the host never sees.
    val hasMicMute: Boolean = false,
) {
    Xbox,
    Xbox360,
    PlayStation(hasLightbar = true),
    DualSense(hasLightbar = true, hasMicMute = true),
    Switch,
}

// Satellite catalog id → skin. The catalog's only Xbox is the emulated
// Xbox 360 pad, so unknown ids fall back to its skin.
fun gamepadSkinForControllerType(type: Int): GamepadSkin =
    when (type) {
        CONTROLLER_TYPE_PLAYSTATION -> GamepadSkin.PlayStation
        CONTROLLER_TYPE_DUALSENSE -> GamepadSkin.DualSense
        CONTROLLER_TYPE_SWITCHPRO -> GamepadSkin.Switch
        else -> GamepadSkin.Xbox360
    }

// Moonlight emulated type → skin, in the Moonlight id table (its ids overlap the
// catalog's, so the two mappers never share a caller). AUTO must be resolved to a
// concrete type first (resolveMoonlightEmulatedType); an unresolved value lands
// on the generic Xbox skin, matching the wire's own fallback.
fun gamepadSkinForMoonlightType(type: Int): GamepadSkin =
    when (type) {
        PLAYSTATION -> GamepadSkin.PlayStation
        NINTENDO -> GamepadSkin.Switch
        else -> GamepadSkin.Xbox
    }

fun gamepadSkinForBtProfile(profileName: String?): GamepadSkin =
    if (profileName == GamepadProfile.PLAYSTATION.profileName) GamepadSkin.PlayStation else GamepadSkin.Xbox

fun gamepadSkinFromName(name: String?): GamepadSkin = GamepadSkin.entries.firstOrNull { it.name == name } ?: GamepadSkin.Xbox
