// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val wakeState: WakeStateController,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
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
