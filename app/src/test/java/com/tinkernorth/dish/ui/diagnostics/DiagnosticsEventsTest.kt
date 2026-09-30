// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class DiagnosticsEventsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `an event line is the clock time, the tag in brackets and the message`() {
        val entry = DiagnosticsLogEntry(atMs = THIRTEEN_OH_SEVEN_AND_NINE_UTC_MS, tag = "usb", message = "claimed 054C:0CE6")
        assertEquals("13:07:09 [usb] claimed 054C:0CE6", formatEvent(entry, utc))
    }

    @Test
    fun `the clock keeps every field two digits wide`() {
        assertEquals("00:00:00 [t] m", formatEvent(DiagnosticsLogEntry(atMs = 0L, tag = "t", message = "m"), utc))
    }

    private companion object {
        const val THIRTEEN_OH_SEVEN_AND_NINE_UTC_MS = (13 * 3600 + 7 * 60 + 9) * 1000L
    }
}
