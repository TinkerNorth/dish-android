// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Dedicated prefs file (not connection_store.xml) so the opt-out survives reinstall via cloud backup.
@Singleton
class CrashReportingStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<Boolean>(
            initialState = readInitial(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun setEnabled(enabled: Boolean) {
            prefs.edit { putBoolean(KEY_COLLECTION_ENABLED, enabled) }
            setState(enabled)
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_COLLECTION_ENABLED = "crashlytics_collection_enabled"
            const val DEFAULT_ENABLED = true

            private fun readInitial(context: Context): Boolean {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return prefs.getBoolean(KEY_COLLECTION_ENABLED, DEFAULT_ENABLED)
            }
        }
    }
