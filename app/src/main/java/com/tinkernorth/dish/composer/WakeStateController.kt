// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeStateController
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val composer: WakeStateComposer,
        private val scope: CoroutineScope,
    ) : AbstractStateSource<WakeState>(WakeState.Idle) {
        private val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        private val _streamingSlotCount = MutableStateFlow(0)
        val streamingSlotCount: StateFlow<Int> = _streamingSlotCount.asStateFlow()

        private val _shouldKeepScreenOn = MutableStateFlow(false)
        val shouldKeepScreenOn: StateFlow<Boolean> = _shouldKeepScreenOn.asStateFlow()

        private val lock = Any()
        private var wakeLock: PowerManager.WakeLock? = null
        private var stopped = false
        private var job: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            synchronized(lock) { stopped = false }
            job =
                composer.state
                    .onEach(::apply)
                    .launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            synchronized(lock) {
                stopped = true
                release()
            }
            job?.cancel()
            job = null
            setState(WakeState.Idle)
            _streamingSlotCount.value = 0
            _shouldKeepScreenOn.value = false
        }

        private fun apply(wake: WakeState) {
            setState(wake)
            _streamingSlotCount.value = wake.streamingSlotCount
            synchronized(lock) {
                if (stopped) return
                val keep = wake.shouldKeepScreenOn
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
