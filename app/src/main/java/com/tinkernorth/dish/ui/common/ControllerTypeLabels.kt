// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType

// Bundled label for a catalog id; the live catalog name wins where available
// (ConfigureBindingsViewModel.typeLabel), this is the offline/diagnostic fallback.
@StringRes
fun bundledControllerTypeLabelRes(type: Int): Int =
    when (type) {
        CONTROLLER_TYPE_PLAYSTATION -> R.string.picker_type_playstation
        CONTROLLER_TYPE_DUALSENSE -> R.string.picker_type_dualsense
        CONTROLLER_TYPE_SWITCHPRO -> R.string.picker_type_switchpro
        else -> R.string.picker_type_xbox
    }

// A Moonlight host runs its own type table whose ids overlap the catalog's, so the two
// never share a label mapper: CONTROLLER_TYPE_XBOX is 1 here and 0 there.
@StringRes
fun moonlightTypeLabelRes(type: Int): Int =
    when (type) {
        MoonlightEmulatedType.XBOX -> R.string.ml_type_xbox
        MoonlightEmulatedType.PLAYSTATION -> R.string.ml_type_playstation
        MoonlightEmulatedType.NINTENDO -> R.string.ml_type_nintendo
        else -> R.string.ml_type_auto
    }

// Silhouette of the pad the host builds for a catalog id; the catalog's Xbox is
// the emulated Xbox 360 pad, so unknown ids fall back to its art.
@DrawableRes
fun bundledControllerTypeGlyphRes(type: Int): Int =
    when (type) {
        CONTROLLER_TYPE_PLAYSTATION -> R.drawable.ic_ctrl_ds4
        CONTROLLER_TYPE_DUALSENSE -> R.drawable.ic_ctrl_dualsense
        CONTROLLER_TYPE_SWITCHPRO -> R.drawable.ic_ctrl_switchpro
        else -> R.drawable.ic_ctrl_xbox360
    }

// Same split as the label mappers: Moonlight ids, with Auto keeping the generic pad.
@DrawableRes
fun moonlightTypeGlyphRes(type: Int): Int =
    when (type) {
        MoonlightEmulatedType.XBOX -> R.drawable.ic_ctrl_xbox
        MoonlightEmulatedType.PLAYSTATION -> R.drawable.ic_ctrl_ds4
        MoonlightEmulatedType.NINTENDO -> R.drawable.ic_ctrl_switchpro
        else -> R.drawable.ic_gamepad
    }
