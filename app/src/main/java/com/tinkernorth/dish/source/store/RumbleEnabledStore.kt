// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Per-slot rumble on/off, defaulting to on. Kept as its own SharedPreferences
// store (rather than riding the descriptor) so RumbleRouter can suppress delivery
// locally without changing the wire caps; the host still advertises rumble.
@Singleton
class RumbleEnabledStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<Map<String, Boolean>>(initialState = readAll(context)) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun setEnabled(
            slotId: String,
            enabled: Boolean,
        ) {
            prefs.edit { putBoolean(key(slotId), enabled) }
            setState { it + (slotId to enabled) }
        }

        // Absent entry collapses to DEFAULT_ENABLED; the raw map keeps absence distinct.
        fun isEnabled(slotId: String): Boolean = state.value[slotId] ?: DEFAULT_ENABLED

        companion object {
            const val DEFAULT_ENABLED: Boolean = true
            private const val PREFS_NAME = "user_preferences"
            private const val PREFIX = "rumble_enabled:"

            private fun key(slotId: String): String = "$PREFIX$slotId"

            private fun readAll(context: Context): Map<String, Boolean> =
                context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .all
                    .asSequence()
                    .filter { it.key.startsWith(PREFIX) && it.value is Boolean }
                    .associate { it.key.removePrefix(PREFIX) to (it.value as Boolean) }
        }
    }
