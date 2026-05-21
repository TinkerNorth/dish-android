// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lowpower

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.R
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import java.util.Calendar
import java.util.Locale

/**
 * Low-power mode state machine: tracks user inactivity while a screen-on lock is
 * held, shows a 5-second countdown banner, then dims the screen and presents a
 * minimal overlay. Touch interaction either exits or resets the state.
 *
 * **Pattern:** [AbstractStateSource]`<State>` — owns one external input
 * (touch + timer expirations), exposes a single `state: StateFlow<State>`, no
 * events. The lifecycle hook [onStop] is driven by the host (typically
 * `GamepadActivityHost`) since this is per-Activity rather than process-scoped;
 * the host calls [onStop] from its `cancelDimOnStop()` path.
 */
class LowPowerManager(
    private val window: Window,
) : AbstractStateSource<LowPowerManager.State>(State.IDLE) {
    /** Bind to the layout views once after inflation. */
    data class Views(
        val llCountdownBanner: LinearLayout,
        val tvCountdownSeconds: TextView,
        val flLowPowerOverlay: FrameLayout,
        val tvLowPowerTime: TextView,
        val tvLowPowerStatus: TextView,
    )

    var views: Views? = null

    /** Provide current controller count for the overlay status line. */
    var activeControllerCount: () -> Int = { 0 }

    enum class State { IDLE, COUNTDOWN, ACTIVE }

    private var savedBrightness = -1f
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var countdownTimer: CountDownTimer? = null
    private val clockHandler = Handler(Looper.getMainLooper())

    private val inactivityRunnable = Runnable { startCountdown() }

    // ── Public API ────────────────────────────────────────────────────────

    /** Called when wake/screen lock state changes. */
    fun onLockStateChanged(active: Boolean) {
        if (active && state.value == State.IDLE) {
            resetInactivityTimer()
        } else if (!active) {
            cancel()
        }
    }

    /** User touched the screen while locks are active. */
    fun onUserInteraction() {
        when (state.value) {
            // Re-arm explicitly after exit(): `shouldKeepScreenOn` is a
            // StateFlow that doesn't re-emit `true` for an already-true value,
            // so onLockStateChanged won't fire a second time. Without this,
            // dismissing the dim once leaves the inactivity timer un-posted
            // until the next touch — a user who dismisses and walks away
            // never sees the overlay return.
            State.ACTIVE -> {
                exit()
                resetInactivityTimer()
            }
            State.COUNTDOWN -> {
                countdownTimer?.cancel()
                countdownTimer = null
                views?.llCountdownBanner?.visibility = View.GONE
                setState(State.IDLE)
                resetInactivityTimer()
            }
            State.IDLE -> resetInactivityTimer()
        }
    }

    /** Tear down everything (connection dropped, activity destroyed). */
    fun cancel() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        exit()
    }

    /** [AbstractStateSource] hook — alias for [cancel]. */
    override fun onStop(owner: LifecycleOwner) {
        cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (state.value == State.IDLE) {
            inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_DELAY_MS)
        }
    }

    private fun startCountdown() {
        setState(State.COUNTDOWN)
        val v = views ?: return
        v.llCountdownBanner.visibility = View.VISIBLE
        v.tvCountdownSeconds.text = COUNTDOWN_SECONDS.toString()
        countdownTimer?.cancel()
        countdownTimer =
            object : CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    v.tvCountdownSeconds.text = ((millisUntilFinished / 1000) + 1).toString()
                }

                override fun onFinish() {
                    enter()
                }
            }.start()
    }

    private fun enter() {
        setState(State.ACTIVE)
        val v = views ?: return
        v.llCountdownBanner.visibility = View.GONE
        val lp = window.attributes
        savedBrightness = lp.screenBrightness
        lp.screenBrightness = MIN_BRIGHTNESS
        window.attributes = lp
        v.flLowPowerOverlay.visibility = View.VISIBLE
        updateStatus()
        startClock()
    }

    private fun exit() {
        if (state.value == State.IDLE) return
        setState(State.IDLE)
        countdownTimer?.cancel()
        countdownTimer = null
        val v = views
        v?.llCountdownBanner?.visibility = View.GONE
        v?.flLowPowerOverlay?.visibility = View.GONE
        stopClock()
        val lp = window.attributes
        lp.screenBrightness = if (savedBrightness >= 0) savedBrightness else -1f
        window.attributes = lp
        savedBrightness = -1f
    }

    /**
     * Re-evaluate the status line if the dim overlay is currently up. Called by
     * the host activity whenever the wake-state controller's bound-slot count
     * changes, so the line keeps up with bind/unbind mid-dim instead of
     * waiting for the next 15-second clock tick.
     */
    fun refreshStatus() {
        if (state.value == State.ACTIVE) updateStatus()
    }

    private fun updateStatus() {
        val v = views ?: return
        val active = activeControllerCount()
        val ctx = v.tvLowPowerStatus.context
        v.tvLowPowerStatus.text =
            if (active > 0) {
                // "Bound" mirrors the bind/unbind concept on the dashboard;
                // we previously said "Streaming" which conflated the routing
                // state with the on-the-wire transmission state.
                ctx.resources.getQuantityString(R.plurals.low_power_status_bound, active, active)
            } else {
                ctx.getString(R.string.low_power_status_idle)
            }
    }

    private val clockRunnable =
        object : Runnable {
            override fun run() {
                if (state.value != State.ACTIVE) return
                val now = Calendar.getInstance()
                views?.tvLowPowerTime?.text =
                    String.format(
                        Locale.ROOT,
                        "%02d:%02d",
                        now.get(Calendar.HOUR_OF_DAY),
                        now.get(Calendar.MINUTE),
                    )
                updateStatus()
                clockHandler.postDelayed(this, 15_000L)
            }
        }

    private fun startClock() {
        clockRunnable.run()
    }

    private fun stopClock() {
        clockHandler.removeCallbacks(clockRunnable)
    }

    companion object {
        private const val INACTIVITY_DELAY_MS = 15_000L
        private const val COUNTDOWN_SECONDS = 5
        private const val MIN_BRIGHTNESS = 0.01f
    }
}
