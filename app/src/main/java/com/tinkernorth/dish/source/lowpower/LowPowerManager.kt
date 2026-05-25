// SPDX-License-Identifier: LGPL-3.0-or-later

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

class LowPowerManager(
    private val window: Window,
) : AbstractStateSource<LowPowerManager.State>(State.IDLE) {
    data class Views(
        val llCountdownBanner: LinearLayout,
        val tvCountdownSeconds: TextView,
        val flLowPowerOverlay: FrameLayout,
        val tvLowPowerTime: TextView,
        val tvLowPowerStatus: TextView,
    )

    var views: Views? = null

    var activeControllerCount: () -> Int = { 0 }

    enum class State { IDLE, COUNTDOWN, ACTIVE }

    private var savedBrightness = -1f
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var countdownTimer: CountDownTimer? = null
    private val clockHandler = Handler(Looper.getMainLooper())

    private val inactivityRunnable = Runnable { startCountdown() }

    fun onLockStateChanged(active: Boolean) {
        if (active && state.value == State.IDLE) {
            resetInactivityTimer()
        } else if (!active) {
            cancel()
        }
    }

    fun onUserInteraction() {
        when (state.value) {
            // Re-arm explicitly: shouldKeepScreenOn StateFlow won't re-emit true so onLockStateChanged is silent.
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

    fun cancel() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        exit()
    }

    override fun onStop(owner: LifecycleOwner) {
        cancel()
    }

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
        v.tvCountdownSeconds.text = String.format(Locale.getDefault(), "%d", COUNTDOWN_SECONDS)
        countdownTimer?.cancel()
        countdownTimer =
            object : CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsRemaining = (millisUntilFinished / 1000) + 1
                    v.tvCountdownSeconds.text = String.format(Locale.getDefault(), "%d", secondsRemaining)
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

    fun refreshStatus() {
        if (state.value == State.ACTIVE) updateStatus()
    }

    private fun updateStatus() {
        val v = views ?: return
        val active = activeControllerCount()
        val ctx = v.tvLowPowerStatus.context
        v.tvLowPowerStatus.text =
            if (active > 0) {
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
