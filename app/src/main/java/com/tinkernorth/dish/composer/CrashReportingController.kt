// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.source.store.CrashReportingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashReportingController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: CrashReportingStore,
        scope: CoroutineScope,
    ) : AbstractController<Boolean>(scope) {
        override fun upstream(): Flow<Boolean> = store.state

        // Process-scoped: do not cancel. Must propagate opt-in flips across activity restarts.
        override fun onStop(owner: LifecycleOwner) = Unit

        override fun apply(value: Boolean) {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.i(
                    TAG,
                    "Firebase not initialised (no google-services.json). Opt-in preference recorded but Crashlytics call skipped.",
                )
                return
            }
            runCatching {
                FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = value
            }.onFailure {
                Log.e(TAG, "Failed to apply Crashlytics collection=$value", it)
            }
        }

        private companion object {
            const val TAG = "CrashReportingCtrl"
        }
    }
