// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.integration

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GitHub build asks for tips. Settings carries every donation surface a layout has (the
 * toolbar heart on a phone, the floating pill and the support card everywhere), each in the
 * flavor's own words.
 */
@RunWith(AndroidJUnit4::class)
class DonationSurfacesTest {
    @Test
    fun settingsShowsTheToolbarHeartWhereTheLayoutHasOne() {
        onSettings { activity ->
            val heart = activity.findViewById<View>(R.id.btnDonate)
            assumeTrue("the tablet Settings layout has no toolbar heart", heart != null)
            assertEquals(View.VISIBLE, heart.visibility)
        }
    }

    @Test
    fun settingsShowsTheSupportCardInTheFlavorsWords() {
        onSettings { activity ->
            val card = activity.findViewById<View>(R.id.cardSupport)
            val body = card.findViewById<TextView>(R.id.cardRowSubtitle).text.toString()

            assertEquals(View.VISIBLE, card.visibility)
            assertEquals(activity.getString(R.string.settings_support_body), body)
        }
    }

    @Test
    fun settingsShowsTheDonatePillInTheFlavorsWords() {
        onSettings { activity ->
            val pill = activity.findViewById<View>(R.id.donatePill)
            val ask = pill.findViewById<TextView>(R.id.donatePillAsk).text.toString()

            assertEquals(activity.getString(R.string.donate_bar_ask), ask)
        }
    }

    private fun onSettings(check: (SettingsActivity) -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario -> scenario.onActivity { check(it) } }
    }
}
