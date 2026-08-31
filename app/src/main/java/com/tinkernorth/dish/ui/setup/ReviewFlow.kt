// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.databinding.BindingPillBinding

// One thing a node in the data-flow sends or gets: a feature icon plus its label.
// Shared by the configure review and the destination picker so a destination's
// capabilities read identically wherever they appear.
data class ReviewFlow(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
)

// The destination-facing chip rows, shared by the bind-controller host picker and
// the setup review: what a destination GETS from this phone and what it SENDS
// back, one chip per feature the path can carry.
internal fun destinationGetFlows(potential: CapabilitySet): List<ReviewFlow> =
    buildList {
        add(ReviewFlow(R.drawable.ic_gamepad, R.string.setup_cfg_flow_controller))
        if (Feature.MOTION in potential) add(ReviewFlow(R.drawable.ic_motion, R.string.binding_func_gyro))
        if (Feature.TOUCHPAD in potential) add(ReviewFlow(R.drawable.ic_touchpad, R.string.touchpad_mode_pad))
        if (Feature.MOUSE in potential) add(ReviewFlow(R.drawable.ic_mouse, R.string.touchpad_mode_mouse))
        if (Feature.BATTERY in potential) add(ReviewFlow(R.drawable.ic_battery, R.string.setup_cap_battery))
    }

internal fun destinationSendFlows(potential: CapabilitySet): List<ReviewFlow> =
    buildList {
        if (Feature.RUMBLE in potential) add(ReviewFlow(R.drawable.ic_rumble, R.string.binding_func_rumble))
        if (Feature.TRIGGER_RUMBLE in potential) {
            add(ReviewFlow(R.drawable.ic_trigger_rumble, R.string.setup_cap_trigger_rumble))
        }
        if (Feature.LIGHTBAR in potential) add(ReviewFlow(R.drawable.ic_lightbar, R.string.setup_cap_lightbar))
        if (Feature.TRIGGER_EFFECTS in potential) {
            add(ReviewFlow(R.drawable.ic_trigger_effects, R.string.setup_cap_trigger_effects))
        }
        if (Feature.PLAYER_LEDS in potential) add(ReviewFlow(R.drawable.ic_player_leds, R.string.setup_cap_player_leds))
    }

// Fills a sends/gets chip row, hiding the whole row when there is nothing to show.
fun AppCompatActivity.bindReviewFlows(
    row: View,
    chips: ViewGroup,
    flows: List<ReviewFlow>,
) {
    row.isVisible = flows.isNotEmpty()
    chips.removeAllViews()
    flows.forEach { flow ->
        val pill = BindingPillBinding.inflate(layoutInflater, chips, false)
        pill.root.setBackgroundResource(R.drawable.bg_binding_pill_cap)
        pill.ivPillIcon.setImageResource(flow.icon)
        pill.ivPillIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.colorOnSurfaceVariant))
        pill.tvPillText.setText(flow.label)
        pill.tvPillText.setTextColor(getColor(R.color.colorOnSurfaceVariant))
        chips.addView(pill.root)
    }
}
