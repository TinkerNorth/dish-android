// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.update

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.update.UpdateNoticePhase
import com.tinkernorth.dish.source.update.UpdateNoticeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePillCopyTest {
    private fun available(required: Boolean) =
        UpdateNoticeStatus(
            phase = UpdateNoticePhase.Available,
            availableVersion = "2.4.0",
            downloadUrl = "https://example.invalid/dish-2.4.0.apk",
            required = required,
        )

    @Test
    fun `the pill is hidden unless a release is on offer`() {
        val quiet = UpdateNoticePhase.entries.filter { it != UpdateNoticePhase.Available }
        for (phase in quiet) {
            assertNull("$phase must hide the pill", updatePillCopy(UpdateNoticeStatus(phase = phase, availableVersion = "2.4.0")))
        }
    }

    @Test
    fun `a required update hides the dismiss and names the version in the ask`() {
        val copy = updatePillCopy(available(required = true))
        assertEquals(PillLine.Bare(R.string.update_pill_headline_required), copy?.headline)
        assertEquals(PillLine.WithVersion(R.string.update_pill_ask_required, "2.4.0"), copy?.ask)
        assertFalse(copy?.dismissible == true)
    }

    @Test
    fun `an optional update names the version in the headline and can be dismissed`() {
        val copy = updatePillCopy(available(required = false))
        assertEquals(PillLine.WithVersion(R.string.update_pill_headline, "2.4.0"), copy?.headline)
        assertEquals(PillLine.Bare(R.string.update_pill_ask), copy?.ask)
        assertTrue(copy?.dismissible == true)
    }

    @Test
    fun `the tap opens the release on offer`() {
        assertEquals("https://example.invalid/dish-2.4.0.apk", updatePillCopy(available(required = false))?.downloadUrl)
    }
}
