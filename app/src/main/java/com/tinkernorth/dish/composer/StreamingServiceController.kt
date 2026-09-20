// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.directClaimCount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val wakeState: WakeStateController,
        private val usbGamepadManager: UsbGamepadManager,
        private val liveness: StreamingServiceLiveness,
        private val crashReporting: CrashReportingController,
        scope: CoroutineScope,
    ) : AbstractController<StreamingServiceController.Input>(scope) {
        data class Input(
            val work: Int,
            val serviceLive: Boolean,
        )

        private var lastWork = 0
        private var startPending = false

        override fun upstream(): Flow<Input> =
            combine(wakeState.streamingSlotCount, usbGamepadManager.controllers, liveness.state) { slots, controllers, live ->
                Input(slots + controllers.directClaimCount(), live)
            }

        override fun onStarting() {
            lastWork = 0
            startPending = false
        }

        override fun apply(value: Input) {
            val rising = value.work > 0 && lastWork <= 0
            lastWork = value.work
            if (value.serviceLive) {
                startPending = false
                return
            }
            if (rising && !startPending) startService()
        }

        private fun startService() {
            startPending =
                try {
                    ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
                    true
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "foreground service start refused: ${e.message}")
                    crashReporting.recordNonFatal(e)
                    false
                }
        }

        private companion object {
            const val TAG = "StreamingServiceController"
        }
    }
