// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tinkernorth.dish.source.store.CrashReportingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [CrashReportingStore]'s reactive opt-in state to Firebase
 * Crashlytics's collection toggle.
 *
 * **Pattern:** [DefaultLifecycleObserver]; installed against
 * `ProcessLifecycleOwner` from [com.tinkernorth.dish.DishApplication]. Collects
 * the store's state for the lifetime of the process and pushes every change
 * to `FirebaseCrashlytics.setCrashlyticsCollectionEnabled` — including the
 * initial value at app start, so a user who opted out on a previous launch
 * gets that respected before any crash can be uploaded.
 *
 * **Firebase-absent builds.** When the build was assembled without
 * `app/google-services.json`, the `google-services` Gradle plugin did not
 * generate the Firebase resources and `FirebaseApp.initializeApp` was never
 * called by the auto-init ContentProvider. We detect that via
 * [FirebaseApp.getApps] and no-op — the store still records the preference
 * (so it'll be honoured on a future build that has Firebase configured),
 * but we skip the SDK call to avoid `IllegalStateException("Default
 * FirebaseApp is not initialized...")`.
 */
@Singleton
class CrashReportingController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: CrashReportingStore,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private var job: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            job =
                store.state
                    .onEach(::applyToCrashlytics)
                    .launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            // Intentionally do not cancel: the controller is process-scoped via
            // ProcessLifecycleOwner. onStop fires when the LAST activity stops,
            // but we still want the next launch's first opt-in / opt-out flip to
            // propagate — the collector outliving onStop is the right shape.
        }

        private fun applyToCrashlytics(enabled: Boolean) {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.i(
                    TAG,
                    "Firebase not initialised (no google-services.json) — opt-in preference recorded but Crashlytics call skipped.",
                )
                return
            }
            runCatching {
                FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
            }.onFailure {
                Log.e(TAG, "Failed to apply Crashlytics collection=$enabled", it)
            }
        }

        private companion object {
            const val TAG = "CrashReportingCtrl"
        }
    }
