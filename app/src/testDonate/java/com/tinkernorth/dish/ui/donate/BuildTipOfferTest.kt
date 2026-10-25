// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.content.Context
import com.tinkernorth.dish.R
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Runs for both flavors with a donate screen, github and play, against each one's own copy.
class BuildTipOfferTest {
    private val ask: TipOffer.Ask get() = TIP_OFFER as TipOffer.Ask

    @Test
    fun `a build with a donate screen asks for tips`() {
        assertEquals(TipOffer.Ask::class, TIP_OFFER::class)
    }

    @Test
    fun `the pill asks in the flavor's own words`() {
        assertEquals(R.string.donate_bar_ask, ask.pillAsk)
    }

    @Test
    fun `the settings card names the flavor's own way to tip`() {
        assertEquals(R.string.settings_support_body, ask.settingsBody)
    }

    @Test
    fun `a fresh install is not a supporter yet, so the pill still asks`() {
        val freshInstall = mockk<Context>(relaxed = true)

        assertFalse(ask.supporters.isSupporter(freshInstall))
    }
}
