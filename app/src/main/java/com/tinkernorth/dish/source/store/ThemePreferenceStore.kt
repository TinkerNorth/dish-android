// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(
    val nightModeFlag: Int,
) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
    ;

    fun toStorageValue(): String =
        when (this) {
            LIGHT -> STORAGE_LIGHT
            DARK -> STORAGE_DARK
            SYSTEM -> STORAGE_SYSTEM
        }
}

// Storage values are user-facing through cloud-backup of user_preferences.xml; renaming
// is a schema migration. Add new values, do not rename existing ones.
private const val STORAGE_SYSTEM = "system"
private const val STORAGE_LIGHT = "light"
private const val STORAGE_DARK = "dark"

// Anything this build does not know, including nothing stored yet, follows the system.
internal fun themeModeFromStorage(value: String?): ThemeMode =
    when (value) {
        STORAGE_LIGHT -> ThemeMode.LIGHT
        STORAGE_DARK -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

// Stored in user_preferences (cloud-backed) rather than connection_store (excluded from backup
// because it holds shared keys). setMode is the controller: persisting and flipping
// AppCompatDelegate live cannot drift apart.
@Singleton
class ThemePreferenceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<ThemeMode>(
            initialState = readInitialThemeMode(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Thread-safe; AppCompatDelegate forces every live AppCompatActivity (including
        // GameActivity-derived MainActivity) to recreate with the new colour primitives.
        fun setMode(mode: ThemeMode) {
            prefs.edit { putString(KEY_THEME_MODE, mode.toStorageValue()) }
            setState(mode)
            AppCompatDelegate.setDefaultNightMode(mode.nightModeFlag)
        }

        fun applyPersistedMode() {
            AppCompatDelegate.setDefaultNightMode(state.value.nightModeFlag)
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_THEME_MODE = "theme_mode"
        }
    }

private fun readInitialThemeMode(context: Context): ThemeMode {
    val prefs = context.getSharedPreferences(ThemePreferenceStore.PREFS_NAME, Context.MODE_PRIVATE)
    return themeModeFromStorage(prefs.getString(ThemePreferenceStore.KEY_THEME_MODE, null))
}
