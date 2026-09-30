// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context
import io.mockk.verify

// Backup schema, spelled out rather than read from the stores: a user's restored preferences
// come back only under the file names and keys the build that wrote them used.
internal const val USER_PREFERENCES_FILE = "user_preferences"

// A store reads and writes its one file and no other.
internal fun verifyOnlyPrefsFile(
    context: Context,
    fileName: String,
) {
    verify { context.getSharedPreferences(fileName, Context.MODE_PRIVATE) }
    verify(exactly = 0) { context.getSharedPreferences(neq(fileName), any()) }
}
