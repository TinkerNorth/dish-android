// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One of three appearance modes the user can pick in Settings → Appearance.
 *
 * - [SYSTEM] follows the device-wide day/night switch (the default — the
 *   one most users expect when they install).
 * - [LIGHT] / [DARK] override the system setting unconditionally for this
 *   app. Their AppCompat night-mode flags drive Android's resource
 *   resolution the same way a system uiMode change does, so the colour
 *   ramp in `values/colors.xml` vs `values-night/colors.xml` flips, the
 *   system bar icons re-pick their style via [SystemBarStyle.auto], and
 *   every non-config-changes-suppressed Activity recreates with the new
 *   colours wired through.
 *
 * The string storage values (`system` / `light` / `dark`) are stable —
 * changing them is a schema migration on a user-facing preference (the
 * choice is included in cloud backup, see [PREFS_NAME]). If the slot
 * gains a new value, prefer adding rather than renaming.
 */
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

    companion object {
        private const val STORAGE_SYSTEM = "system"
        private const val STORAGE_LIGHT = "light"
        private const val STORAGE_DARK = "dark"

        /** Inverse of [toStorageValue]. Unknown / null falls back to [SYSTEM]. */
        fun fromStorageValue(value: String?): ThemeMode =
            when (value) {
                STORAGE_LIGHT -> LIGHT
                STORAGE_DARK -> DARK
                else -> SYSTEM
            }
    }
}

/**
 * User preference for the in-app theme — light / dark / follow system.
 *
 * **Pattern:** [AbstractStateSource]`<ThemeMode>` — reactive store, same
 * shape as [CrashReportingStore]. Backed by the shared `user_preferences`
 * SharedPreferences file so the choice survives reinstalls via cloud
 * backup (the `connection_store.xml` file is *excluded* from backup
 * because it holds shared keys; user preferences belong in a backed-up
 * file).
 *
 * Default is **[ThemeMode.SYSTEM]** — matches what most users expect on
 * first launch (the app follows their device-wide day/night switch). The
 * other two modes are explicit overrides.
 *
 * **The controller is the store itself**, not a separate composer:
 * [setMode] persists the new value AND immediately flips
 * `AppCompatDelegate.setDefaultNightMode`. This couples the persisted
 * state with the live AppCompat configuration — they cannot drift. The
 * trade-off (mixing IO + framework call in a single store method)
 * versus the alternative (separate composer observing [state] and
 * forwarding to AppCompatDelegate) is a deliberate simplicity choice:
 * the framework call is process-wide and idempotent, so there's no
 * win from layering an observer between the persisted preference and
 * the framework state.
 *
 * Application bootstrap calls [applyPersistedMode] from
 * [com.tinkernorth.dish.DishApplication.onCreate] so the persisted choice
 * is applied BEFORE any Activity inflates. Subsequent runtime changes go
 * through [setMode], which both persists and re-applies.
 */
@Singleton
class ThemePreferenceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<ThemeMode>(
            initialState = readInitial(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /**
         * Persist [mode], emit it on [state], AND flip AppCompatDelegate's
         * default night mode. Every live AppCompatActivity recreates with
         * the new colour primitives wired through (AppCompatDelegate
         * iterates its active delegates and forces a recreate). Both
         * Dish activities that can be on screen at the same time — the
         * SettingsActivity that calls this method and the MainActivity
         * underneath it — extend AppCompatActivity (MainActivity via
         * `com.google.androidgamesdk.GameActivity`, which transitively
         * extends AppCompatActivity), so neither needs a manual
         * `recreate()` to pick up the new mode.
         *
         * Safe to call from any thread. The AppCompatDelegate call is
         * thread-safe internally.
         */
        fun setMode(mode: ThemeMode) {
            prefs.edit { putString(KEY_THEME_MODE, mode.toStorageValue()) }
            setState(mode)
            AppCompatDelegate.setDefaultNightMode(mode.nightModeFlag)
        }

        /**
         * Apply the persisted mode to [AppCompatDelegate] without writing
         * back to SharedPreferences. Called from
         * [com.tinkernorth.dish.DishApplication.onCreate] so the in-app
         * setting takes effect before the first Activity inflates.
         *
         * Idempotent — Android tolerates the same mode being set repeatedly.
         */
        fun applyPersistedMode() {
            AppCompatDelegate.setDefaultNightMode(state.value.nightModeFlag)
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_THEME_MODE = "theme_mode"

            private fun readInitial(context: Context): ThemeMode {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return ThemeMode.fromStorageValue(prefs.getString(KEY_THEME_MODE, null))
            }
        }
    }
