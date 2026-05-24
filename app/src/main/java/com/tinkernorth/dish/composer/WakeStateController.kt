// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

/**
 * Process-scoped wake-lock + "keep the screen on" coordinator.
 *
 * **Pattern split:**
 *
 *   - Pure derivation (the count + boolean) lives in [WakeStateComposer] — a
 *     [com.tinkernorth.dish.architecture.abstracts.AbstractComposer] of `WakeState`.
 *   - This class owns the *side effect* — acquiring and releasing the
 *     [PowerManager.PARTIAL_WAKE_LOCK] based on what the composer emits — and is
 *     therefore a [AbstractStateSource]. The "state" it exposes through the base is
 *     a snapshot of what the composer is currently producing; the
 *     `AbstractStateSource` shape lets it observe `ProcessLifecycleOwner.onStart/onStop`
 *     and drop the wake lock when the app backgrounds.
 *
 * Two flows are exposed for callers (Activities + StreamingService):
 *
 *   - [streamingSlotCount] — number of slots actively streaming
 *   - [shouldKeepScreenOn] — true iff [streamingSlotCount] > 0 (drives the window flag)
 *
 * Both are zeroed on `onStop` regardless of what the underlying composer is producing,
 * because a backgrounded app should not be "streaming" from the user's point of view —
 * the wake lock has been released, so the dashboard / overlay must agree.
 *
 * **Thread safety:** the composer's `onEach` runs on the app scope's dispatcher
 * while `onStop` runs on the main thread (ProcessLifecycleOwner). All wake-lock
 * mutations and the `stopped` gate live under [lock] so a late onEach emission
 * cannot re-acquire after onStop has decided to release.
 */
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

        /**
         * Mirror [wake] into the exposed flows and drive the wake-lock side
         * effect. The wake-lock toggle and the gate are guarded so a late
         * emission delivered after [onStop] cannot strand a held lock.
         */
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

        /** Call under [lock] only. */
        private fun acquire() {
            if (wakeLock?.isHeld == true) return
            wakeLock =
                powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }

        /** Call under [lock] only. */
        private fun release() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }

        private companion object {
            const val WAKE_LOCK_TAG = "Dish::ControllerStream"

            // Safety-net timeout: the OS auto-releases the wake lock after
            // [WAKE_LOCK_TIMEOUT_MS] even if [release] never fires. The
            // foreground service is the actual keep-alive for streaming, so
            // an expiration mid-stream doesn't drop the session — it just
            // means CPU can sleep with the screen between samples. 60 minutes
            // covers the longest realistic single-session stream without
            // refreshing; if a session runs past it, the composer's next
            // emission will re-acquire on the same code path that drives the
            // window flag.
            const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        }
    }
