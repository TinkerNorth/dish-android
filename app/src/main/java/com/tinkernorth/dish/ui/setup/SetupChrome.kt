// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.res.ColorStateList
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ViewSetupBreadcrumbBinding

const val SETUP_STEP_INPUT = 0
const val SETUP_STEP_DESTINATION = 1
const val SETUP_STEP_BINDING = 2

// Fills every bar up to and including the active step (so progress reads as
// "how far along", not just "where am I"); only the active step's label
// brightens to primary.
fun ViewSetupBreadcrumbBinding.applyStep(activeStep: Int) {
    val rows = listOf(stepBar1 to stepLabel1, stepBar2 to stepLabel2, stepBar3 to stepLabel3)
    val ctx = root.context
    rows.forEachIndexed { index, (bar, label) ->
        val barColor = if (index <= activeStep) R.color.colorPrimary else R.color.colorSurfaceVariant
        bar.backgroundTintList = ColorStateList.valueOf(ctx.getColor(barColor))
        label.setTextColor(ctx.getColor(if (index == activeStep) R.color.colorPrimary else R.color.colorMuted))
    }
}
