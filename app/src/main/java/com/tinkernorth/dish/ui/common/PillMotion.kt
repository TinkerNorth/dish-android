// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.view.View
import com.tinkernorth.dish.R

// The bottom pills (donate, update) share one entrance and one exit so they
// move alike when both are on screen.
internal fun Activity.slidePillIn(pill: View) {
    if (animationsDisabled()) {
        pill.alpha = 1f
        pill.translationY = 0f
        return
    }
    pill.alpha = 0f
    pill.translationY = resources.getDimensionPixelSize(R.dimen.spacing_6xl).toFloat()
    pill
        .animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(resources.getInteger(R.integer.motion_duration_medium).toLong())
        .start()
}

internal fun slidePillOut(
    pill: View,
    onHidden: () -> Unit,
) {
    pill
        .animate()
        .alpha(0f)
        .translationY(pill.height.toFloat())
        .setDuration(pill.resources.getInteger(R.integer.motion_duration_medium).toLong())
        .withEndAction { onHidden() }
        .start()
}
