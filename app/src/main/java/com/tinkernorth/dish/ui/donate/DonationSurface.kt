// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.donate

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.common.DishNavigator

// Every screen shares these touchpoints (with the pill in DonatePill.kt); the flavors part ways
// inside DonateActivity, which github and play each supply: external payment rails on github,
// Play Billing on play, as Google Play's Payments policy requires of Play-distributed apps.
fun AppCompatActivity.wireDonateButton() {
    findViewById<View>(R.id.btnDonate)?.setOnClickListener { openDonateScreen() }
}

fun AppCompatActivity.bindDonateSettingsCard() {
    val card = findViewById<View>(R.id.cardSupport) ?: return
    card.isVisible = true
    // Scoped to the card: `card_row_icon_label_value` is included several
    // times in Settings, so these ids are unique only within one card.
    card.findViewById<ImageView>(R.id.cardRowIcon)?.apply {
        setImageResource(R.drawable.ic_heart)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPulse))
    }
    card.findViewById<TextView>(R.id.cardRowTitle)?.setText(R.string.settings_support_title)
    card.findViewById<TextView>(R.id.cardRowSubtitle)?.setText(R.string.settings_support_body)
    card.setOnClickListener { openDonateScreen() }
}

internal fun AppCompatActivity.openDonateScreen() {
    DishNavigator(this).toDonate()
}
