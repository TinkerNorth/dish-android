// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.BindingPillBinding

// One thing a node in the data-flow sends or gets: a feature icon plus its label.
// Shared by the configure review and the destination picker so a destination's
// capabilities read identically wherever they appear.
data class ReviewFlow(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
)

// Fills a sends/gets chip row, hiding the whole row when there is nothing to show.
fun AppCompatActivity.bindReviewFlows(
    row: View,
    chips: LinearLayout,
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
