// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ReportCategory
import com.tinkernorth.dish.hotpath.input.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The report screen's choices: where it starts, what an unpicked category means, which pads it offers.
class SupportReportTest {
    private fun diag(
        slotId: String,
        virtual: Boolean = false,
    ) = ControllerDiag(
        slotId = slotId,
        name = "Pad $slotId",
        isVirtual = virtual,
        transport = if (virtual) null else Transport.Usb,
        isUsbSynthetic = false,
        hasGyro = false,
        pollRateHz = 0,
        gyroHz = 0,
        state = ControllerDiagState.CONNECTED,
        battery = null,
        host = null,
        functions = emptyList(),
    )

    @Test
    fun `asking a question starts on the question category`() {
        assertEquals(ReportCategory.QUESTION, startCategoryOf(isQuestion = true))
    }

    @Test
    fun `reporting a problem leaves the category to the person`() {
        assertNull(startCategoryOf(isQuestion = false))
    }

    @Test
    fun `a category left unpicked is something else`() {
        assertEquals(ReportCategory.OTHER, reportCategoryOrOther(null))
        assertEquals(ReportCategory.AUDIO, reportCategoryOrOther(ReportCategory.AUDIO))
    }

    @Test
    fun `the report offers every physical pad and never the on-screen one`() {
        val choices = reportChoicesOf(listOf(diag("virtual", virtual = true), diag("4"), diag("-5")))
        assertEquals(listOf(ReportControllerChoice("4", "Pad 4"), ReportControllerChoice("-5", "Pad -5")), choices)
    }
}
