// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.update

import com.tinkernorth.dish.core.update.UpdatePhase
import com.tinkernorth.dish.core.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the screens see of the updater's state: every phase, and the fields a notice renders. */
class UpdateNoticeOfTest {
    private fun phaseSeenFor(phase: UpdatePhase): UpdateNoticePhase = noticeOf(UpdateStatus(CURRENT, phase = phase)).phase

    @Test
    fun `every updater phase reaches the screens as the notice phase of the same name`() {
        assertEquals(UpdateNoticePhase.Disabled, phaseSeenFor(UpdatePhase.Disabled))
        assertEquals(UpdateNoticePhase.Idle, phaseSeenFor(UpdatePhase.Idle))
        assertEquals(UpdateNoticePhase.Checking, phaseSeenFor(UpdatePhase.Checking))
        assertEquals(UpdateNoticePhase.UpToDate, phaseSeenFor(UpdatePhase.UpToDate))
        assertEquals(UpdateNoticePhase.Available, phaseSeenFor(UpdatePhase.Available))
        assertEquals(UpdateNoticePhase.Failed, phaseSeenFor(UpdatePhase.Failed))
    }

    @Test
    fun `an offered update carries its version, its link, whether it is required and the switch`() {
        val status =
            UpdateStatus(
                CURRENT,
                phase = UpdatePhase.Available,
                availableVersion = OFFERED,
                downloadUrl = LINK,
                checksEnabled = false,
                required = true,
            )

        val notice = noticeOf(status)

        assertEquals(OFFERED, notice.availableVersion)
        assertEquals(LINK, notice.downloadUrl)
        assertEquals(true, notice.required)
        assertEquals(false, notice.checksEnabled)
    }

    private companion object {
        const val CURRENT = "2.0.0"
        const val OFFERED = "2.1.0"
        const val LINK = "https://example.invalid/dish.apk"
    }
}
