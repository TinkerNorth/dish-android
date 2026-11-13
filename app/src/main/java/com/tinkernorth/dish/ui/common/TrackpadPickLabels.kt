// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.repository.TrackpadPick

@StringRes
fun trackpadPickLabelRes(pick: TrackpadPick): Int =
    when (pick) {
        TrackpadPick.STICK -> R.string.trackpad_pick_stick
        TrackpadPick.PAD -> R.string.trackpad_pick_pad
        TrackpadPick.MOUSE -> R.string.trackpad_pick_mouse
        TrackpadPick.OFF -> R.string.trackpad_pick_off
    }
