// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.integration

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.donate.DonateActivity
import com.tinkernorth.dish.ui.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Amazon build asks for no tips. Settings carries every donation surface a layout has (the
 * toolbar heart on a phone, the floating pill and the support card everywhere), so none may show.
 */
@RunWith(AndroidJUnit4::class)
class DonationSurfacesTest {
    @Test
    fun settingsShowsNoToolbarHeart() {
        onSettings { activity ->
            val heartShown = activity.findViewById<View>(R.id.btnDonate)?.isShown == true
            assertFalse(heartShown)
        }
    }

    @Test
    fun settingsShowsNoSupportCard() {
        onSettings { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.cardSupport).visibility)
        }
    }

    @Test
    fun settingsShowsNoDonatePill() {
        onSettings { activity ->
            assertNull(activity.findViewById<View>(R.id.donatePill))
        }
    }

    @Test
    fun theDonateScreenClosesTheMomentItOpens() {
        ActivityScenario.launch(DonateActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    private fun onSettings(check: (SettingsActivity) -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario -> scenario.onActivity { check(it) } }
    }
}
