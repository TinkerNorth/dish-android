// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Opt-in latency profiling. Default OFF: when off the native hot-path bench is a single relaxed
// atomic load per mark, so leaving it disabled keeps the input path free of measurement work. The
// flag persists so a session the user explicitly armed re-arms on next launch (they already
// accepted the warning), but a fresh install stays off until they turn it on.
@Singleton
class LatencyProfilingStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<Boolean>(
            initialState = readInitial(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun setEnabled(enabled: Boolean) {
            prefs.edit { putBoolean(KEY, enabled) }
            setState(enabled)
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY = "latency_profiling_enabled"
            const val DEFAULT_ENABLED = false

            private fun readInitial(context: Context): Boolean {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return prefs.getBoolean(KEY, DEFAULT_ENABLED)
            }
        }
    }
