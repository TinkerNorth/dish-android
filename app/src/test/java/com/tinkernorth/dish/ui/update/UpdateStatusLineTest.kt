// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.update

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.update.UpdateNoticePhase
import com.tinkernorth.dish.source.update.UpdateNoticeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStatusLineTest {
    @Test
    fun `every quiet phase is the check button with its own body`() {
        val bodies =
            mapOf(
                UpdateNoticePhase.Idle to R.string.update_status_idle,
                UpdateNoticePhase.Checking to R.string.update_status_checking,
                UpdateNoticePhase.UpToDate to R.string.update_status_up_to_date,
                UpdateNoticePhase.Failed to R.string.update_status_failed,
            )
        for ((phase, body) in bodies) {
            val line = updateStatusLine(UpdateNoticeStatus(phase = phase))
            assertEquals(phase.name, R.string.settings_update_check_now_title, line.title)
            assertEquals(phase.name, body, line.body)
            assertNull(phase.name, line.versionArg)
            assertTrue(phase.name, line.actionable)
        }
    }

    @Test
    fun `checks off is the one row that does nothing`() {
        val line = updateStatusLine(UpdateNoticeStatus(phase = UpdateNoticePhase.Disabled, checksEnabled = false))
        assertEquals(R.string.update_status_off, line.body)
        assertFalse(line.actionable)
    }

    @Test
    fun `an available release becomes the download button with the version`() {
        val line =
            updateStatusLine(
                UpdateNoticeStatus(phase = UpdateNoticePhase.Available, availableVersion = "2.1.0", downloadUrl = "https://github.com/x"),
            )
        assertEquals(R.string.settings_update_download_title, line.title)
        assertEquals(R.string.update_status_available, line.body)
        assertEquals("2.1.0", line.versionArg)
        assertTrue(line.actionable)
    }

    @Test
    fun `a required release says so`() {
        val line = updateStatusLine(UpdateNoticeStatus(phase = UpdateNoticePhase.Available, availableVersion = "2.1.0", required = true))
        assertEquals(R.string.settings_update_download_title, line.title)
        assertEquals(R.string.update_status_required, line.body)
        assertEquals("2.1.0", line.versionArg)
    }
}
