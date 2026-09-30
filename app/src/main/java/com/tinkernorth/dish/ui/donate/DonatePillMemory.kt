// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.content.Context
import androidx.core.content.edit

private const val DONATE_PILL_PREFS = "user_preferences"
private const val DONATE_PILL_DISMISSED_AT = "donate_pill_dismissed_at"
private const val DONATE_PILL_DISMISS_WINDOW_MS = 24L * 60L * 60L * 1000L

// A dismissed pill stays away for a day, then asks again.
internal fun donatePillDismissed(context: Context): Boolean {
    val dismissedAt =
        context
            .getSharedPreferences(DONATE_PILL_PREFS, Context.MODE_PRIVATE)
            .getLong(DONATE_PILL_DISMISSED_AT, 0L)
    return System.currentTimeMillis() - dismissedAt < DONATE_PILL_DISMISS_WINDOW_MS
}

internal fun dismissDonatePill(context: Context) {
    context
        .getSharedPreferences(DONATE_PILL_PREFS, Context.MODE_PRIVATE)
        .edit { putLong(DONATE_PILL_DISMISSED_AT, System.currentTimeMillis()) }
}
