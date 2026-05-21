// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped controller that starts and stops [StreamingService] in response to
 * [WakeStateController.streamingSlotCount]. Registered as a
 * `ProcessLifecycleOwner` observer so the service is also stopped when the app is
 * fully backgrounded and the wake state collapses.
 *
 * **Pattern:** [AbstractStateSource]`<Unit>` — the controller has no
 * own state and no events; it is purely a lifecycle-bound side-effect dispatcher.
 * The `Unit` and `Nothing` type parameters declare those absences honestly.
 */
@Singleton
class StreamingServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val wakeState: WakeStateController,
        private val scope: CoroutineScope,
    ) : AbstractStateSource<Unit>(Unit) {
        private var job: Job? = null
        private var running = false

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            job =
                wakeState.streamingSlotCount
                    .onEach { count ->
                        val shouldRun = count > 0
                        if (shouldRun && !running) {
                            startService()
                        } else if (!shouldRun && running) {
                            stopService()
                        }
                    }.launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            job?.cancel()
            job = null
            if (running) stopService()
        }

        private fun startService() {
            // Skip on API 33+ if POST_NOTIFICATIONS is denied — the channel is LOW
            // importance and won't pop a heads-up, but starting a service without
            // a visible notification (denied permission means silent) is fine; the
            // foreground type carries the work-affordance.
            val intent = Intent(context, StreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            running = true
        }

        private fun stopService() {
            context.stopService(Intent(context, StreamingService::class.java))
            running = false
        }
    }
