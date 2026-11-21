// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.controllerdb.VERIFICATION_FLAGGED
import com.tinkernorth.dish.core.controllerdb.VERIFICATION_VERIFIED
import com.tinkernorth.dish.core.diagnostics.WriteRoute
import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import com.tinkernorth.dish.core.diagnostics.errName
import com.tinkernorth.dish.core.diagnostics.reportIdHex

private const val MS_PER_SECOND = 1000L

// Verified, flagged, and everything else (imported, crowd, guessed) in the app's existing words for
// a layout nobody has confirmed: "Layout guessed".
@StringRes
internal fun verificationLabel(state: String): Int =
    when (state) {
        VERIFICATION_VERIFIED -> R.string.diagnostics_verification_verified
        VERIFICATION_FLAGGED -> R.string.diagnostics_verification_flagged
        else -> R.string.diagnostics_verification_guessed
    }

// The record, its name, its chip, this phone's known issues and the expect mismatches.
internal fun Context.recordLines(facts: PadFacts): List<String> {
    val lines = mutableListOf<String>()
    facts.record?.let { record ->
        lines += diagKv(R.string.diagnostics_record, record.id)
        record.name?.let { lines += diagKv(R.string.diagnostics_record_name, it) }
        lines += diagKv(R.string.diagnostics_verification, getString(verificationLabel(record.verification)))
        record.issues.forEach { issue -> lines += issueLines(issue) }
    }
    facts.expectMismatches.forEach { lines += diagKv(R.string.diagnostics_expect_mismatch, it) }
    facts.direct?.firmware?.let { lines += diagKv(R.string.diagnostics_firmware, it) }
    return lines
}

private fun Context.issueLines(issue: IssueFacts): List<String> =
    listOfNotNull(
        diagKv(R.string.diagnostics_known_issue, issue.title ?: issue.id),
        issue.body,
    )

// "report 02 · bulk OUT 0x03 · ok, 12 ms ago", "report 02 · no OUT endpoint",
// "report 02 · bulk OUT 0x03 · EPIPE": docs/controller-db.md section 9's Last write row.
internal fun Context.writeVerdictLine(
    verdict: WriteVerdict,
    nowMs: Long,
): String {
    val report = getString(R.string.write_verdict_report, reportIdHex(verdict.reportId))
    val route =
        when (verdict.route) {
            WriteRoute.NONE -> return getString(R.string.diagnostics_joined, report, getString(R.string.write_verdict_no_out))
            WriteRoute.INTERRUPT_OUT -> getString(R.string.write_verdict_bulk_out, verdict.endpoint)
            WriteRoute.SET_REPORT -> getString(R.string.write_verdict_set_report)
        }
    val outcome = if (verdict.ok) getString(R.string.write_verdict_ok, writeAgeLabel(verdict.ageMs, nowMs)) else errName(verdict.err)
    return getString(R.string.write_verdict_line, report, route, outcome)
}

private fun Context.writeAgeLabel(
    ageMs: Long,
    nowMs: Long,
): String {
    val underASecond = ageMs < MS_PER_SECOND
    if (underASecond) return getString(R.string.diagnostics_ms_ago, ageMs.coerceAtLeast(0L))
    return agoLabel(nowMs, nowMs - ageMs)
}

internal fun Context.lastWriteLine(
    facts: PadFacts,
    nowMs: Long,
): String? {
    val verdict = facts.lastWrite ?: return null
    return diagKv(R.string.diagnostics_last_write, writeVerdictLine(verdict, nowMs))
}
