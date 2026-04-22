package com.tinkernorth.dish.util

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * Low-power mode state machine.
 *
 * Tracks user inactivity while locks are held, shows a countdown, then
 * dims the screen and displays a minimal overlay.  Touch interaction
 * exits or resets the state.
 */
class LowPowerManager(
    private val window: Window,
) {
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

    var state = State.IDLE
        private set

    private var savedBrightness = -1f
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var countdownTimer: CountDownTimer? = null
    private val clockHandler = Handler(Looper.getMainLooper())

    private val inactivityRunnable = Runnable { startCountdown() }

    // ── Public API ────────────────────────────────────────────────────────

    /** Called when wake/screen lock state changes. */
    fun onLockStateChanged(active: Boolean) {
        if (active && state == State.IDLE) {
            resetInactivityTimer()
        } else if (!active) {
            cancel()
        }
    }

    /** User touched the screen while locks are active. */
    fun onUserInteraction() {
        when (state) {
            State.ACTIVE -> exit()
            State.COUNTDOWN -> {
                countdownTimer?.cancel()
                countdownTimer = null
                views?.llCountdownBanner?.visibility = View.GONE
                state = State.IDLE
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

    // ── Internal ──────────────────────────────────────────────────────────

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (state == State.IDLE) {
            inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_DELAY_MS)
        }
    }

    private fun startCountdown() {
        state = State.COUNTDOWN
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
        state = State.ACTIVE
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
        if (state == State.IDLE) return
        state = State.IDLE
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

    private fun updateStatus() {
        val v = views ?: return
        val active = activeControllerCount()
        v.tvLowPowerStatus.text =
            if (active > 0) {
                "Streaming · $active controller${if (active > 1) "s" else ""}"
            } else {
                "Connected"
            }
    }

    private val clockRunnable =
        object : Runnable {
            override fun run() {
                if (state != State.ACTIVE) return
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
