// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.ControllerDbSource
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDbLabelsTest {
    private val status = ControllerDbStatus.UNLOADED.copy(bundledVersion = 1, activeVersion = 1)

    @Test
    fun `check now is enabled while the switch is on and no check runs`() {
        assertTrue(controllerDbCheckNowEnabled(status.copy(checksEnabled = true)))
    }

    @Test
    fun `check now is disabled while the switch is off`() {
        assertFalse(controllerDbCheckNowEnabled(status.copy(checksEnabled = false)))
    }

    @Test
    fun `check now is disabled while a check runs`() {
        assertFalse(controllerDbCheckNowEnabled(status.copy(checking = true)))
    }

    @Test
    fun `a running check reads as checking before any last result`() {
        val running = status.copy(checking = true, lastCheckResult = ControllerDbCheckResult.UPDATED)
        assertEquals(R.string.controller_db_result_checking, controllerDbStatusResultLabel(running))
        assertEquals(R.string.controller_db_result_updated, controllerDbStatusResultLabel(running.copy(checking = false)))
    }

    @Test
    fun `never checked and each result have their words`() {
        assertEquals(R.string.controller_db_result_never, controllerDbResultLabel(null))
        assertEquals(R.string.controller_db_result_off, controllerDbResultLabel(ControllerDbCheckResult.OFF))
        assertEquals(R.string.controller_db_result_offline, controllerDbResultLabel(ControllerDbCheckResult.OFFLINE))
        assertEquals(R.string.controller_db_result_invalid, controllerDbResultLabel(ControllerDbCheckResult.INVALID))
        assertEquals(R.string.controller_db_result_up_to_date, controllerDbResultLabel(ControllerDbCheckResult.UP_TO_DATE))
    }

    @Test
    fun `the copy in use is named by its source`() {
        assertEquals(R.string.controller_db_source_bundled, controllerDbSourceLabel(ControllerDbSource.BUNDLED))
        assertEquals(R.string.controller_db_source_cache, controllerDbSourceLabel(ControllerDbSource.CACHE))
    }
}
