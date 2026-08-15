// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleOwner
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
        scope: CoroutineScope,
    ) : AbstractController<Int>(scope) {
        private var running = false

        // Held Direct claims count alongside streaming slots: a claimed pad has been reconfigured at
        // the device level, and only a live process can run the restore that hands it back.
        override fun upstream(): Flow<Int> =
            combine(wakeState.streamingSlotCount, usbGamepadManager.controllers) { slots, controllers ->
                slots + controllers.directClaimCount()
            }

        override fun apply(value: Int) {
            val shouldRun = value > 0
            if (shouldRun && !running) {
                startService()
            } else if (!shouldRun && running) {
                stopService()
            }
        }

        // The service may have stopped itself while collection was down (claims released in the
        // background), so re-derive `running` from the fresh post-start emission; a redundant start
        // against a live service is harmless.
        override fun onStarting() {
            running = false
        }

        override fun onStop(owner: LifecycleOwner) {
            cancelCollection()
            // WakeState zeroes the slot count on process stop, but a held claim keeps the service
            // (and with it the process) alive; the service watches the claims itself and exits when
            // the last one goes.
            if (running && usbGamepadManager.controllers.value.directClaimCount() == 0) stopService()
        }

        private fun startService() {
            val intent = Intent(context, StreamingService::class.java)
            running =
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    true
                } catch (e: IllegalStateException) {
                    // Android 12+ can refuse a background FGS start (ForegroundServiceStartNotAllowedException);
                    // stay not-running so the next foreground slot-count change retries instead of crashing.
                    Log.w(TAG, "foreground service start refused: ${e.message}")
                    false
                }
        }

        private fun stopService() {
            context.stopService(Intent(context, StreamingService::class.java))
            running = false
        }

        private companion object {
            const val TAG = "StreamingServiceController"
        }
    }
