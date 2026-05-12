// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped wake-lock + "keep the screen on" coordinator.
 *
 * Owns a single [PowerManager.PARTIAL_WAKE_LOCK] and exposes a
 * [shouldKeepScreenOn] flag derived from the [ConnectionHub]'s bindings +
 * connection liveness. The wake lock lives here (not on any [android.app.Activity])
 * so the [com.tinkernorth.dish.ui.main.MainActivity] →
 * [com.tinkernorth.dish.ui.main.GamepadOverlayActivity] transition no longer
 * bounces it. `FLAG_KEEP_SCREEN_ON` is window-scoped so each activity must
 * still toggle that itself, but both activities now collect the same flag
 * from this controller — and so see identical screen-on behaviour.
 *
 * The collector is gated on the *process* lifecycle (via
 * [androidx.lifecycle.ProcessLifecycleOwner]): we drop the wake lock when
 * the whole app is backgrounded, since a partial wake lock that survives
 * backgrounding would burn the battery without any visible payoff.
 */
@Singleton
class WakeStateController
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val hub: ConnectionHub,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        private val _streamingSlotCount = MutableStateFlow(0)

        /**
         * Number of slots currently routing input to a live connection. Same
         * semantics as [com.tinkernorth.dish.ui.main.MainUiState.streamingSlotCount]
         * but derived from hub state directly so the gamepad-overlay activity
         * (which doesn't see [com.tinkernorth.dish.ui.main.MainViewModel]) can
         * read it too.
         */
        val streamingSlotCount: StateFlow<Int> = _streamingSlotCount.asStateFlow()

        private val _shouldKeepScreenOn = MutableStateFlow(false)
        val shouldKeepScreenOn: StateFlow<Boolean> = _shouldKeepScreenOn.asStateFlow()

        @Volatile private var wakeLock: PowerManager.WakeLock? = null
        private var job: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            job =
                combine(hub.bindings, hub.connections) { bindings, conns ->
                    val byId = conns.associateBy { it.id }
                    bindings.values.count { cid -> byId[cid]?.live == ConnectionLive.CONNECTED }
                }.onEach { count ->
                    _streamingSlotCount.value = count
                    val keep = count > 0
                    if (keep == _shouldKeepScreenOn.value) return@onEach
                    _shouldKeepScreenOn.value = keep
                    if (keep) acquire() else release()
                }.launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            job?.cancel()
            job = null
            _streamingSlotCount.value = 0
            _shouldKeepScreenOn.value = false
            release()
        }

        private fun acquire() {
            if (wakeLock?.isHeld == true) return
            wakeLock =
                powerManager
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG,
                    ).apply { acquire() }
        }

        private fun release() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }

        private companion object {
            const val WAKE_LOCK_TAG = "Dish::ControllerStream"
        }
    }
