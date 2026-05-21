// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preference for whether Firebase Crashlytics may collect crash reports.
 *
 * **Pattern:** [AbstractStateSource]`<Boolean>` — reactive boolean store.
 * Backed by a dedicated [SharedPreferences] file ([PREFS_NAME]) so the choice
 * survives reinstalls via cloud backup. We deliberately do NOT reuse
 * `connection_store.xml`: that file is excluded from auto-backup because it
 * holds shared keys; the crash-reporting opt-out is a user preference we
 * want to honour across device transfers.
 *
 * Default is **true** (collection enabled), matching the legal posture in
 * [`PRIVACY.md`]: users have notice + an in-app opt-out, which is the
 * "legitimate interest" basis under GDPR for diagnostic crash data. Flip the
 * default to `false` if the project ever moves to explicit consent.
 *
 * **The controller**, not this store, talks to Firebase.
 * [com.tinkernorth.dish.composer.CrashReportingController] observes the state
 * and calls `FirebaseCrashlytics.setCrashlyticsCollectionEnabled` — keeps the
 * store testable without Firebase on the classpath.
 */
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

        /** Persist [enabled] and emit it on [state]. Safe to call from any thread. */
        fun setEnabled(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_COLLECTION_ENABLED, enabled).apply()
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
