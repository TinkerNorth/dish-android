// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ControllerDbPreferences(
    val checksEnabled: Boolean = true,
    val lastCheckAtMs: Long = 0L,
    val lastCheckResult: ControllerDbCheckResult? = null,
    val etag: String? = null,
)

// "Check for controller updates automatically" and the last check, in user_preferences.xml beside
// the app's other switches so the choice survives a reinstall via cloud backup.
@Singleton
class ControllerDbPreferenceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<ControllerDbPreferences>(
            initialState = readControllerDbPreferences(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun setChecksEnabled(enabled: Boolean) {
            prefs.edit { putBoolean(KEY_CHECKS_ENABLED, enabled) }
            setState { it.copy(checksEnabled = enabled) }
        }

        fun recordCheck(
            atMs: Long,
            result: ControllerDbCheckResult,
            etag: String?,
        ) {
            prefs.edit {
                putLong(KEY_LAST_CHECK_MS, atMs)
                putString(KEY_LAST_CHECK_RESULT, result.wire)
                putString(KEY_ETAG, etag)
            }
            setState { it.copy(lastCheckAtMs = atMs, lastCheckResult = result, etag = etag) }
        }

        // The manifest's etag says which download the cache holds; one that no longer reads has none.
        fun forgetEtag() {
            prefs.edit { remove(KEY_ETAG) }
            setState { it.copy(etag = null) }
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_CHECKS_ENABLED = "controller_db_checks_enabled"
            const val KEY_LAST_CHECK_MS = "controller_db_last_check_utc_ms"
            const val KEY_LAST_CHECK_RESULT = "controller_db_last_check_result"
            const val KEY_ETAG = "controller_db_manifest_etag"
            const val DEFAULT_CHECKS_ENABLED = true
        }
    }

private fun readControllerDbPreferences(context: Context): ControllerDbPreferences {
    val prefs = context.getSharedPreferences(ControllerDbPreferenceStore.PREFS_NAME, Context.MODE_PRIVATE)
    val resultName = prefs.getString(ControllerDbPreferenceStore.KEY_LAST_CHECK_RESULT, null)
    return ControllerDbPreferences(
        checksEnabled =
            prefs.getBoolean(
                ControllerDbPreferenceStore.KEY_CHECKS_ENABLED,
                ControllerDbPreferenceStore.DEFAULT_CHECKS_ENABLED,
            ),
        lastCheckAtMs = prefs.getLong(ControllerDbPreferenceStore.KEY_LAST_CHECK_MS, 0L),
        lastCheckResult = ControllerDbCheckResult.entries.firstOrNull { it.wire == resultName },
        etag = prefs.getString(ControllerDbPreferenceStore.KEY_ETAG, null),
    )
}
