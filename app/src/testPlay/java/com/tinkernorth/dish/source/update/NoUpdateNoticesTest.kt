// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoUpdateNoticesTest {
    private val notices = NoUpdateNotices()

    private val disabled = UpdateNoticeStatus(phase = UpdateNoticePhase.Disabled, checksEnabled = false)

    @Test
    fun `the play build reports no update support`() {
        assertFalse(notices.supported)
    }

    @Test
    fun `the status is disabled with checks off, so no screen offers an update`() {
        assertEquals(disabled, notices.status.value)
    }

    @Test
    fun `the settings switch, the check button and the skip change nothing`() {
        notices.setChecksEnabled(true)
        notices.checkNow()
        notices.skipAvailableVersion()

        assertEquals(disabled, notices.status.value)
    }
}
