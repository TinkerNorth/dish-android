// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.donate

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tinkernorth.dish.R

/**
 * Play-flavor donation surface: there isn't one.
 *
 * Google Play's Payments policy treats in-app donation links as digital
 * payments that have to run through Play Billing unless the developer is a
 * verified tax-exempt organization, which Tinker North is not. Rather than
 * take a billing integration (and Google's service fee) for a tip jar, the
 * Play build ships without the donation screen, the pill, the toolbar heart,
 * and the Settings support card. The `github` flavor keeps all of it.
 *
 * These are no-op twins of the `github` source set's functions so the shared
 * activities compile against one API. The donate screen, its layouts, its
 * copy, and the payment URLs live in `src/github` and are absent from the
 * Play artifact entirely — not merely hidden at runtime.
 */
fun AppCompatActivity.attachDonatePill() = Unit

fun AppCompatActivity.wireDonateButton() = Unit

/** Hides the Settings support card, which the shared layout still declares. */
fun AppCompatActivity.bindDonateSettingsCard() {
    findViewById<View>(R.id.cardSupport)?.isVisible = false
}
