// SPDX-License-Identifier: LGPL-3.0-or-later

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
            // Process-scoped: do not cancel — must propagate opt-in flips across activity restarts.
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
