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
        val statusRes =
            when {
                row.available -> R.string.setup_cap_available
                row.unknown -> R.string.setup_cap_unknown
                else -> R.string.setup_cap_off
            }
        rowBinding.capStatus.setText(statusRes)
        rowBinding.capStatus.setTextColor(context.getColor(if (row.available) R.color.colorSuccess else R.color.colorMuted))
        if (row.inputUnknown) applyUnknown(rowBinding.icInput) else applyCheck(rowBinding.icInput, row.inputOk)
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
        SetupCapabilityKind.BATTERY -> R.string.setup_cap_battery
        SetupCapabilityKind.LIGHTBAR -> R.string.setup_cap_lightbar
        SetupCapabilityKind.TRIGGER_RUMBLE -> R.string.setup_cap_trigger_rumble
        SetupCapabilityKind.TRIGGER_EFFECTS -> R.string.setup_cap_trigger_effects
        SetupCapabilityKind.PLAYER_LEDS -> R.string.setup_cap_player_leds
        SetupCapabilityKind.MICROPHONE -> R.string.setup_cap_mic
        SetupCapabilityKind.SPEAKER -> R.string.setup_cap_speaker
    }

private fun applyCheck(
    view: ImageView,
    ok: Boolean,
) {
    view.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_cancel)
    view.imageTintList = ColorStateList.valueOf(view.context.getColor(if (ok) R.color.colorSuccess else R.color.colorMuted))
}

private fun applyUnknown(view: ImageView) {
    view.setImageResource(R.drawable.ic_help)
    view.imageTintList = ColorStateList.valueOf(view.context.getColor(R.color.colorMuted))
}
