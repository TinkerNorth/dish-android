// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.SetupCapabilityRowBinding

// Renders the resolved capability rows into a type card's container so the
// Bluetooth-host pick-type screen and the configure screen draw the table the
// same way.
fun LinearLayout.bindCapabilityRows(rows: List<SetupCapabilityRow>) {
    removeAllViews()
    val inflater = LayoutInflater.from(context)
    rows.forEach { row ->
        val rowBinding = SetupCapabilityRowBinding.inflate(inflater, this, false)
        rowBinding.capName.setText(capabilityNameRes(row.kind))
        rowBinding.capStatus.setText(if (row.available) R.string.setup_cap_available else R.string.setup_cap_off)
        rowBinding.capStatus.setTextColor(context.getColor(if (row.available) R.color.colorSuccess else R.color.colorMuted))
        applyCheck(rowBinding.icInput, row.inputOk)
        applyCheck(rowBinding.icDestination, row.destinationOk)
        applyCheck(rowBinding.icType, row.typeOk)
        addView(rowBinding.root)
    }
}

private fun capabilityNameRes(kind: SetupCapabilityKind): Int =
    when (kind) {
        SetupCapabilityKind.RUMBLE -> R.string.setup_cap_rumble
        SetupCapabilityKind.MOTION -> R.string.setup_cap_motion
        SetupCapabilityKind.TOUCHPAD -> R.string.setup_cap_touchpad
    }

private fun applyCheck(
    view: ImageView,
    ok: Boolean,
) {
    view.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_cancel)
    view.imageTintList = ColorStateList.valueOf(view.context.getColor(if (ok) R.color.colorSuccess else R.color.colorMuted))
}
