// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ReportCategory

// The support report's mail: its subject and the order of its body.

@StringRes
fun reportCategoryLabel(category: ReportCategory): Int =
    when (category) {
        ReportCategory.NOT_RECOGNISED -> R.string.report_category_not_recognised
        ReportCategory.NO_FEEDBACK -> R.string.report_category_no_feedback
        ReportCategory.CONNECTION -> R.string.report_category_connection
        ReportCategory.AUDIO -> R.string.report_category_audio
        ReportCategory.OTHER -> R.string.report_category_other
        ReportCategory.QUESTION -> R.string.report_category_question
    }

// "Dish support: No rumble or feedback · Dish for Android 2.7.0 on AYN Thor", the category in the
// person's language.
internal fun Context.reportSubject(
    category: ReportCategory,
    version: String,
    model: String,
): String = getString(R.string.report_subject, getString(reportCategoryLabel(category)), version, model)

// The person's own words first, then the summary; a report with no words opens on the summary.
fun reportMailBody(
    text: String,
    summary: List<String>,
): String {
    val words = text.trim()
    val opening = if (words.isEmpty()) emptyList() else listOf(words, "")
    return (opening + summary).joinToString("\n")
}
