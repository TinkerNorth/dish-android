// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.BindingPillBinding
import com.tinkernorth.dish.ui.common.setLeadingIcon

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
        pill.tvPillText.setLeadingIcon(
            flow.icon,
            R.dimen.binding_pill_icon_size,
            getColor(R.color.colorOnSurfaceVariant),
        )
        pill.tvPillText.setText(flow.label)
        pill.tvPillText.setTextColor(getColor(R.color.colorOnSurfaceVariant))
        chips.addView(pill.root)
    }
}
