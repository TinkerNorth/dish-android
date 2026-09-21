// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class UpdatePreferences(
    val checksEnabled: Boolean = true,
    val skippedVersion: String = "",
)

// The update switch, the one skipped version and the time of the last check.
// In user_preferences.xml beside the crash-reporting opt-out, so the choices
// survive a reinstall via cloud backup. The reactive slice is what the
// reducer sees; the last-check time is read once at startup for the min-gap
// rule and never enters the reducer.
@Singleton
class UpdatePreferenceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<UpdatePreferences>(
            initialState = readInitial(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun setChecksEnabled(enabled: Boolean) {
            prefs.edit { putBoolean(KEY_CHECKS_ENABLED, enabled) }
            setState { it.copy(checksEnabled = enabled) }
        }

        fun setSkippedVersion(version: String) {
            prefs.edit { putString(KEY_SKIPPED_VERSION, version) }
            setState { it.copy(skippedVersion = version) }
        }

        // Wall-clock UTC milliseconds of the last completed check; 0 when none.
        fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK_UTC_MS, 0L)

        fun recordLastCheck(nowMs: Long) {
            prefs.edit { putLong(KEY_LAST_CHECK_UTC_MS, nowMs) }
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_CHECKS_ENABLED = "updates_check_enabled"
            const val KEY_SKIPPED_VERSION = "updates_skipped_version"
            const val KEY_LAST_CHECK_UTC_MS = "updates_last_check_utc_ms"
            const val DEFAULT_CHECKS_ENABLED = true

            private fun readInitial(context: Context): UpdatePreferences {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return UpdatePreferences(
                    checksEnabled = prefs.getBoolean(KEY_CHECKS_ENABLED, DEFAULT_CHECKS_ENABLED),
                    skippedVersion = prefs.getString(KEY_SKIPPED_VERSION, "").orEmpty(),
                )
            }
        }
    }
