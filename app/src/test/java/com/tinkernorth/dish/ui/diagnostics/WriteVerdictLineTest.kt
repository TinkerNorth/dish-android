// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.diagnostics.WriteRoute
import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import io.mockk.MockKAnswerScope
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

// The Last write row in docs/controller-db.md's own English wording, with the strings' English
// templates standing in for the resources.
class WriteVerdictLineTest {
    private val templates =
        mapOf(
            R.string.write_verdict_report to "report %1\$s",
            R.string.write_verdict_bulk_out to "bulk OUT 0x%1\$02X",
            R.string.write_verdict_set_report to "SET_REPORT",
            R.string.write_verdict_no_out to "no OUT endpoint",
            R.string.write_verdict_ok to "OK, %1\$s",
            R.string.write_verdict_line to "%1\$s · %2\$s · %3\$s",
            R.string.diagnostics_joined to "%1\$s · %2\$s",
            R.string.diagnostics_ms_ago to "%1\$d ms ago",
            R.string.diagnostics_seconds_ago to "%1\$d s ago",
        )

    private val context =
        mockk<Context> {
            every { getString(any()) } answers { templates.getValue(firstArg()) }
            every { getString(any(), *anyVararg()) } answers { templates.getValue(firstArg()).format(*formatArgs()) }
        }

    private fun MockKAnswerScope<*, *>.formatArgs(): Array<Any?> = secondArg()

    private fun verdict(
        route: WriteRoute = WriteRoute.INTERRUPT_OUT,
        ok: Boolean = true,
        err: Int = 0,
        ageMs: Long = 12,
    ) = WriteVerdict(route, endpoint = 3, reportId = 2, ok = ok, err = err, ageMs = ageMs)

    @Test
    fun `a write that landed reads with its endpoint and how long ago`() {
        assertEquals("report 02 · bulk OUT 0x03 · OK, 12 ms ago", context.writeVerdictLine(verdict(), NOW_MS))
    }

    @Test
    fun `a pad with no route reads no OUT endpoint`() {
        assertEquals("report 02 · no OUT endpoint", context.writeVerdictLine(verdict(route = WriteRoute.NONE, ok = false), NOW_MS))
    }

    @Test
    fun `a refused write reads by its errno name`() {
        assertEquals("report 02 · bulk OUT 0x03 · EPIPE", context.writeVerdictLine(verdict(ok = false, err = 32), NOW_MS))
    }

    @Test
    fun `a set report write names the control pipe`() {
        assertEquals("report 02 · SET_REPORT · OK, 12 ms ago", context.writeVerdictLine(verdict(route = WriteRoute.SET_REPORT), NOW_MS))
    }

    @Test
    fun `a write a second or more ago reads in seconds`() {
        assertEquals("report 02 · bulk OUT 0x03 · OK, 3 s ago", context.writeVerdictLine(verdict(ageMs = 3_000), NOW_MS))
    }

    private companion object {
        const val NOW_MS = 1_000_000L
    }
}
