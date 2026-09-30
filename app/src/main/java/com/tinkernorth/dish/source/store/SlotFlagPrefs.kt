// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context

private const val SLOT_FLAG_PREFS = "user_preferences"

// The per-slot toggles live side by side in one prefs file, each family behind its own prefix.
internal fun slotFlagKey(
    prefix: String,
    slotId: String,
): String = "$prefix$slotId"

// Every entry under [prefix]; an entry of another type is not a toggle and is left out.
internal fun readSlotFlags(
    context: Context,
    prefix: String,
): Map<String, Boolean> =
    context
        .getSharedPreferences(SLOT_FLAG_PREFS, Context.MODE_PRIVATE)
        .all
        .asSequence()
        .filter { it.key.startsWith(prefix) && it.value is Boolean }
        .associate { it.key.removePrefix(prefix) to (it.value as Boolean) }
