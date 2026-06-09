// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeStateController
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val composer: WakeStateComposer,
        scope: CoroutineScope,
    ) : AbstractController<WakeState>(scope) {
        private val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        private val _streamingSlotCount = MutableStateFlow(0)
        val streamingSlotCount: StateFlow<Int> = _streamingSlotCount.asStateFlow()

        private val _shouldKeepScreenOn = MutableStateFlow(false)
        val shouldKeepScreenOn: StateFlow<Boolean> = _shouldKeepScreenOn.asStateFlow()

        private val lock = Any()
        private var wakeLock: PowerManager.WakeLock? = null
        private var stopped = false

        override fun upstream(): Flow<WakeState> = composer.state

        // Re-arm the stopped guard under [lock] before the base launches the collector.
        override fun onStarting() {
            synchronized(lock) { stopped = false }
        }

        override fun onStop(owner: LifecycleOwner) {
            synchronized(lock) {
                stopped = true
                release()
            }
            cancelCollection()
            _streamingSlotCount.value = 0
            _shouldKeepScreenOn.value = false
        }

        override fun apply(value: WakeState) {
            synchronized(lock) {
                // Drop a composer emission that lands after onStop: publishing the count here would let
                // StreamingServiceController restart the foreground service while the app is stopped.
                if (stopped) return
                _streamingSlotCount.value = value.streamingSlotCount
                val keep = value.shouldKeepScreenOn
                if (keep == _shouldKeepScreenOn.value) return
                _shouldKeepScreenOn.value = keep
                if (keep) acquire() else release()
            }
        }

        // Call under [lock] only.
        private fun acquire() {
            if (wakeLock?.isHeld == true) return
            wakeLock =
                powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }

        // Call under [lock] only.
        private fun release() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }

        private companion object {
            const val WAKE_LOCK_TAG = "Dish::ControllerStream"

            // OS safety-net release; foreground service is the actual session keep-alive.
            const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        }
    }
