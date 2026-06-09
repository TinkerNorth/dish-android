// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val wakeState: WakeStateController,
        scope: CoroutineScope,
    ) : AbstractController<Int>(scope) {
        private var running = false

        override fun upstream(): Flow<Int> = wakeState.streamingSlotCount

        override fun apply(value: Int) {
            val shouldRun = value > 0
            if (shouldRun && !running) {
                startService()
            } else if (!shouldRun && running) {
                stopService()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            cancelCollection()
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
