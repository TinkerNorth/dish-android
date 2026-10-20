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

// Every screen shares these touchpoints (with the pill in DonatePill.kt), and each shows only where
// the flavor's TIP_OFFER asks for tips. The flavors part ways inside DonateActivity: external
// payment rails on github, Play Billing on play, as Google Play's Payments policy requires of
// Play-distributed apps. The amazon build asks for nothing, so its DonateActivity is never opened.
fun AppCompatActivity.wireDonateButton() {
    val button = findViewById<View>(R.id.btnDonate) ?: return
    val asksForTips = TIP_OFFER is TipOffer.Ask
    button.isVisible = asksForTips
    if (!asksForTips) return
    button.setOnClickListener { openDonateScreen() }
}

fun AppCompatActivity.bindDonateSettingsCard() {
    val card = findViewById<View>(R.id.cardSupport) ?: return
    val ask = TIP_OFFER as? TipOffer.Ask
    card.isVisible = ask != null
    if (ask == null) return
    // Scoped to the card: `card_row_icon_label_value` is included several
    // times in Settings, so these ids are unique only within one card.
    card.findViewById<ImageView>(R.id.cardRowIcon)?.apply {
        setImageResource(R.drawable.ic_heart)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPulse))
    }
    card.findViewById<TextView>(R.id.cardRowTitle)?.setText(R.string.settings_support_title)
    card.findViewById<TextView>(R.id.cardRowSubtitle)?.setText(ask.settingsBody)
    card.setOnClickListener { openDonateScreen() }
}

internal fun AppCompatActivity.openDonateScreen() {
    DishNavigator(this).toDonate()
}
