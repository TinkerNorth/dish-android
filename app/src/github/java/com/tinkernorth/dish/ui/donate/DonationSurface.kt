// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.donate

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tinkernorth.dish.R

/**
 * Donation surface for the directly-distributed (`github`) build: the
 * toolbar heart, the dismissable pill, and the Settings support card, all of
 * which open [DonateActivity].
 *
 * The `play` source set supplies no-op twins of these functions. Everything
 * donation-related — this file, [DonateActivity], [attachDonatePill], the
 * layouts, the copy, and the payment URLs — is compiled into the github
 * flavor only, so the Play artifact never contains it. See
 * `src/play/java/.../DonationSurface.kt` for the policy background.
 *
 * [DonateActivity] is launched by explicit Intent rather than through
 * `DishNavigator`/`nav_graph.xml`: the graph is shared by both flavors and
 * `NavInflater` resolves every `android:name` at inflation time, so a
 * destination pointing at a class the Play build doesn't have would break
 * navigation for every screen.
 */
fun AppCompatActivity.wireDonateButton() {
    findViewById<View>(R.id.btnDonate)?.setOnClickListener { openDonateScreen() }
}

/** Binds and shows the Settings support card the shared layout declares. */
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
    startActivity(Intent(this, DonateActivity::class.java))
}
