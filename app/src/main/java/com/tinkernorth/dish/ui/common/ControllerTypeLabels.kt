// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO

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
