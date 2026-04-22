package com.tinkernorth.dish.util

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.tinkernorth.dish.ui.main.GamepadInputProcessor

/**
 * Periodically drains telemetry counters from [GamepadInputProcessor] and
 * updates the dashboard TextViews.
 *
 * Owns no mutable state beyond the timer; all data comes from the input
 * processor's [GamepadInputProcessor.drainTelemetry] snapshot.
 */
class TelemetryTracker(
    private val input: GamepadInputProcessor,
) {
    /** Views to update — set once after inflation. */
    data class Views(
        val tvEventRate: TextView,
        val tvSampleRate: TextView,
        val tvSendRate: TextView,
        val tvTotalSent: TextView,
        val tvLX: TextView,
        val tvLY: TextView,
        val tvRX: TextView,
        val tvRY: TextView,
        val tvLT: TextView,
        val tvRT: TextView,
        val tvBtns: TextView,
    )

    var views: Views? = null

    private val handler = Handler(Looper.getMainLooper())
    private val runnable =
        object : Runnable {
            override fun run() {
                update()
                handler.postDelayed(this, INTERVAL_MS)
            }
        }

    fun start() {
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
    }

    internal fun update() {
        val snap = input.drainTelemetry()
        val v = views ?: return
        v.tvEventRate.text = "${snap.events} / s"
        v.tvSampleRate.text = "${snap.samples} / s"
        v.tvSendRate.text = "${snap.sends} / s"
        v.tvTotalSent.text = "${snap.totalSent}"
        v.tvLX.text = "%+6d".format(input.sLX)
        v.tvLY.text = "%+6d".format(input.sLY)
        v.tvRX.text = "%+6d".format(input.sRX)
        v.tvRY.text = "%+6d".format(input.sRY)
        v.tvLT.text = "%3d".format(input.bLT)
        v.tvRT.text = "%3d".format(input.bRT)
        v.tvBtns.text = "0x%04X".format(input.wButtons)
    }

    companion object {
        private const val INTERVAL_MS = 1000L
    }
}
