// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val EVENT_TIME_PATTERN = "HH:mm:ss"

// Log lines are export material (English, fixed clock format), so bug reports paste uniformly.
internal fun formatEvent(
    entry: DiagnosticsLogEntry,
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val format = SimpleDateFormat(EVENT_TIME_PATTERN, Locale.US)
    format.timeZone = zone
    val time = format.format(Date(entry.atMs))
    return "$time [${entry.tag}] ${entry.message}"
}
