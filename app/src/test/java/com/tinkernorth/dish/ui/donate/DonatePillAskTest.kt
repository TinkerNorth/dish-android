// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private const val PILL_ASK = 1
private const val SETTINGS_BODY = 2

class DonatePillAskTest {
    private class SupporterProbe(
        private val answer: Boolean,
    ) {
        val asked = mutableListOf<SupporterCheck>()

        fun isSupporter(check: SupporterCheck): Boolean {
            asked += check
            return answer
        }
    }

    private val ask = TipOffer.Ask(PILL_ASK, SETTINGS_BODY, SupporterCheck { false })

    @Test
    fun `a build that takes no tips shows no pill`() {
        val probe = SupporterProbe(answer = false)

        assertNull(donatePillAsk(TipOffer.None, dismissed = false, isSupporter = probe::isSupporter))
    }

    @Test
    fun `a build that takes no tips asks nobody whether they support it`() {
        val probe = SupporterProbe(answer = false)

        donatePillAsk(TipOffer.None, dismissed = false, isSupporter = probe::isSupporter)

        assertEquals(emptyList<SupporterCheck>(), probe.asked)
    }

    @Test
    fun `a pill dismissed in the last day stays away`() {
        val probe = SupporterProbe(answer = false)

        assertNull(donatePillAsk(ask, dismissed = true, isSupporter = probe::isSupporter))
    }

    @Test
    fun `a dismissed pill skips the supporter check, which on Play re-verifies the plan`() {
        val probe = SupporterProbe(answer = false)

        donatePillAsk(ask, dismissed = true, isSupporter = probe::isSupporter)

        assertEquals(emptyList<SupporterCheck>(), probe.asked)
    }

    @Test
    fun `a supporter sees no pill`() {
        val probe = SupporterProbe(answer = true)

        assertNull(donatePillAsk(ask, dismissed = false, isSupporter = probe::isSupporter))
    }

    @Test
    fun `everyone else sees the build's ask`() {
        val probe = SupporterProbe(answer = false)

        assertSame(ask, donatePillAsk(ask, dismissed = false, isSupporter = probe::isSupporter))
    }

    @Test
    fun `the supporter question goes to the build's own check`() {
        val probe = SupporterProbe(answer = false)

        donatePillAsk(ask, dismissed = false, isSupporter = probe::isSupporter)

        assertEquals(listOf(ask.supporters), probe.asked)
    }
}
