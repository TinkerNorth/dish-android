// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.LinkTier
import com.tinkernorth.dish.ui.main.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTierBadgesTest {
    @Test
    fun `fastest tier reads Fastest`() {
        assertEquals(R.string.link_tier_fastest, tierLabelRes(LinkTier.FASTEST))
    }

    @Test
    fun `fast tier reads Fast`() {
        assertEquals(R.string.link_tier_fast, tierLabelRes(LinkTier.FAST))
    }

    @Test
    fun `basic tier reads Basic`() {
        assertEquals(R.string.link_tier_basic, tierLabelRes(LinkTier.BASIC))
    }

    @Test
    fun `fastest tier wears the bolt, same glyph as the Direct path pill`() {
        assertEquals(R.drawable.ic_bolt, tierIconRes(LinkTier.FASTEST))
    }

    @Test
    fun `fast tier wears the wifi glyph`() {
        assertEquals(R.drawable.ic_wifi, tierIconRes(LinkTier.FAST))
    }

    @Test
    fun `basic tier wears the bluetooth glyph`() {
        assertEquals(R.drawable.ic_bluetooth, tierIconRes(LinkTier.BASIC))
    }

    @Test
    fun `fastest tier lights up in the ON tone, same tone as the Direct path pill`() {
        assertEquals(PillTone.ON, tierTone(LinkTier.FASTEST))
    }

    @Test
    fun `fast tier renders as a plain fact`() {
        assertEquals(PillTone.FACT, tierTone(LinkTier.FAST))
    }

    @Test
    fun `basic tier renders in the muted cap tone, same tone as the Standard path pill`() {
        assertEquals(PillTone.CAP, tierTone(LinkTier.BASIC))
    }

    @Test
    fun `every tier resolves to a non-zero label, icon, and tone`() {
        for (tier in LinkTier.entries) {
            assertTrue("missing label for $tier", tierLabelRes(tier) != 0)
            assertTrue("missing icon for $tier", tierIconRes(tier) != 0)
            tierTone(tier)
        }
    }

    @Test
    fun `each tier has a distinct label, icon, and tone`() {
        assertEquals(LinkTier.entries.size, LinkTier.entries.mapTo(mutableSetOf()) { tierLabelRes(it) }.size)
        assertEquals(LinkTier.entries.size, LinkTier.entries.mapTo(mutableSetOf()) { tierIconRes(it) }.size)
        assertEquals(LinkTier.entries.size, LinkTier.entries.mapTo(mutableSetOf()) { tierTone(it) }.size)
    }
}
