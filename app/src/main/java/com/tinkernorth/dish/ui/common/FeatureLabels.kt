// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.Feature

@StringRes
fun featureLabelRes(feature: Feature): Int? =
    when (feature) {
        Feature.GAMEPAD, Feature.ANALOG_TRIGGERS -> null
        Feature.MOTION -> R.string.setup_cap_motion
        Feature.TOUCHPAD -> R.string.setup_cap_touchpad
        Feature.MOUSE -> R.string.binding_func_mouse
        Feature.KEYBOARD -> R.string.diagnostics_feature_keyboard
        Feature.BATTERY -> R.string.setup_cap_battery
        Feature.RUMBLE -> R.string.setup_cap_rumble
        Feature.TRIGGER_RUMBLE -> R.string.setup_cap_trigger_rumble
        Feature.LIGHTBAR -> R.string.setup_cap_lightbar
        Feature.TRIGGER_EFFECTS -> R.string.setup_cap_trigger_effects
        Feature.PLAYER_LEDS -> R.string.setup_cap_player_leds
        Feature.MIC -> R.string.setup_cap_mic
        Feature.SPEAKER -> R.string.setup_cap_speaker
    }
