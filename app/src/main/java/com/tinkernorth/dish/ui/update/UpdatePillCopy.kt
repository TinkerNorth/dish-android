// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.update

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.update.UpdateNoticePhase
import com.tinkernorth.dish.source.update.UpdateNoticeStatus

// One line of the pill: a bare string, or one that names the version on offer.
internal sealed interface PillLine {
    data class Bare(
        @StringRes val res: Int,
    ) : PillLine

    data class WithVersion(
        @StringRes val res: Int,
        val version: String,
    ) : PillLine
}

internal data class UpdatePillCopy(
    val headline: PillLine,
    val ask: PillLine,
    val dismissible: Boolean,
    val downloadUrl: String,
)

// Null hides the pill: only a release on offer has anything to say. A required update
// leads with the requirement and names the version in the ask, and cannot be skipped; an
// optional one names the version in the headline and offers the dismiss.
internal fun updatePillCopy(status: UpdateNoticeStatus): UpdatePillCopy? {
    if (status.phase != UpdateNoticePhase.Available) return null
    return if (status.required) requiredCopy(status) else optionalCopy(status)
}

private fun requiredCopy(status: UpdateNoticeStatus): UpdatePillCopy =
    UpdatePillCopy(
        headline = PillLine.Bare(R.string.update_pill_headline_required),
        ask = PillLine.WithVersion(R.string.update_pill_ask_required, status.availableVersion),
        dismissible = false,
        downloadUrl = status.downloadUrl,
    )

private fun optionalCopy(status: UpdateNoticeStatus): UpdatePillCopy =
    UpdatePillCopy(
        headline = PillLine.WithVersion(R.string.update_pill_headline, status.availableVersion),
        ask = PillLine.Bare(R.string.update_pill_ask),
        dismissible = true,
        downloadUrl = status.downloadUrl,
    )
