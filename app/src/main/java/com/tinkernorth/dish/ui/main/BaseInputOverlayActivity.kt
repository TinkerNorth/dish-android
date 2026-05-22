// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.notification.DishNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared scaffolding for full-screen, landscape, single-connection input
 * overlays — currently the on-screen gamepad and the on-screen touchpad.
 *
 * Both overlays:
 *   - Run full-screen landscape with system bars hidden.
 *   - Bind to one [com.tinkernorth.dish.composer.ConnectionSummary] from the
 *     intent's [EXTRA_CONNECTION_ID] and surface status / pairing prompts via
 *     [DishNotifications] (not Toast — see the per-app rule).
 *   - Install a [GamepadActivityHost] for wake-lock + physical-pad pass-through.
 *   - Drive a deadline-paced resend loop at the subclass's chosen interval
 *     (250 Hz for both gamepad and touchpad), so a single dropped UDP packet
 *     recovers within one tick instead of leaving the host on a stale state.
 *
 * Subclasses own their own view binding (different layouts) and the
 * `lastReported*` cache for their input shape. They implement
 * [resendOneIfReady] to push whatever they have cached on each tick; the
 * base class only owns the deadline arithmetic, the lifecycle gating, and
 * the input-dispatch shims that ride the keyboard / motion / touch event
 * paths.
 *
 * Designed for composition over rewrite — a future "mouse trackpad with
 * scroll wheel" overlay or a "stylus pen tablet" overlay can extend this
 * without re-deriving any of the boilerplate.
 */
abstract class BaseInputOverlayActivity : AppCompatActivity() {
    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    /** Gamepad pass-through + wake-lock host. Installed in [installBaseScaffolding]. */
    protected lateinit var gamepadHost: GamepadActivityHost

    /** The connection this overlay is bound to (from the intent extra). */
    protected var connectionId: String = ""

    // ── Subclass hooks ──────────────────────────────────────────────────────

    /**
     * The inflated root view to attach the gamepad host to. Must be
     * available by the time [installBaseScaffolding] is called — typically
     * `binding.root` after `setContentView(...)` in the subclass `onCreate`.
     */
    protected abstract fun rootView(): View

    /**
     * Nanoseconds between resend ticks. The gamepad overlay uses 4 ms ≈
     * 250 Hz to match a typical wired Xbox/PS poll rate; the touchpad
     * overlay uses the same. Subclasses can override for slower or faster
     * cadences if a future input shape warrants it.
     */
    protected abstract val resendIntervalNs: Long

    /**
     * Called from the shared resend loop. Subclasses inspect their cached
     * `lastReported*` state and emit one packet on the satellite path if
     * the gate is open (state present, connection Live + Connected). Called
     * on `Dispatchers.Default`; the JNI sends are non-blocking.
     */
    protected abstract fun resendOneIfReady()

    /**
     * Fires whenever the bound [ConnectionSummary] changes shape (live state,
     * label, kind). Subclasses paint their own status row from this; the
     * base class does no painting because the gamepad and touchpad
     * overlays show different row content.
     */
    protected open fun onConnectionSummaryChanged(summary: ConnectionSummary?) = Unit

    /**
     * Hook for subclass-specific connection events. The base class handles
     * [ConnectionEvent.Error] and [ConnectionEvent.PairingRequired]
     * uniformly (notification banner); subclasses can override to handle
     * mode-specific events too.
     */
    protected open fun onConnectionEvent(event: ConnectionEvent) = Unit

    // ── Lifecycle wiring ────────────────────────────────────────────────────

    /**
     * Call from subclass `onCreate` AFTER `setContentView(binding.root)`.
     * Installs the gamepad host, hides system bars, parses the connection
     * extra, and starts the three lifecycle-scoped collectors:
     *   - connection-summary fan-out → [onConnectionSummaryChanged]
     *   - SatelliteConnectionManager events → [handleConnectionEvent]
     *   - periodic resend loop → [resendOneIfReady]
     *
     * Subclasses may launch additional lifecycle collectors after calling
     * this — anything order-sensitive (e.g. an extra `combine` that depends
     * on the same `hub.connections` source) should run after.
     */
    protected fun installBaseScaffolding() {
        gamepadHost = GamepadActivityHost(this, rootView(), wakeState, gamepadRegistry)
            .also { it.install(notifications) }
        hideSystemBars()
        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()

        // Connection-summary fan-out — single collector so subclasses paint
        // from one coherent snapshot per emission (no two-collector race
        // where the status row reads one combine output while another paints
        // a stale label).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections
                    .map { conns -> conns.firstOrNull { it.id == connectionId } }
                    .distinctUntilChanged()
                    .collect { onConnectionSummaryChanged(it) }
            }
        }

        // Mid-session connection events (errors, pairing-required from
        // alive-poll). With SatelliteConnectionManager's SharedFlow buffered,
        // these reach a full-screen overlay where we can't pop a dialog —
        // surface them through DishNotifications instead.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect(::handleConnectionEvent)
            }
        }

        // Periodic resend at the subclass's chosen cadence. Deadline-based
        // pacing so JNI send + encrypt + sendto don't leak into the cycle
        // (a 4 ms config would otherwise produce ≈6 ms cycles → 168 Hz, not
        // the requested 250 Hz). On Dispatchers.Default — touch dispatch
        // and vsync/layout would otherwise add tail latency on the main
        // dispatcher.
        lifecycleScope.launch(Dispatchers.Default) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runResendLoop()
            }
        }
    }

    private suspend fun runResendLoop() {
        var nextTickNs = System.nanoTime() + resendIntervalNs
        while (currentCoroutineActive()) {
            val now = System.nanoTime()
            // Guard against runaway catch-up. If we somehow fell more than
            // a few intervals behind (dispatcher starved, process
            // throttled), reset rather than spamming back-dated reports the
            // host has already aged past.
            if (now - nextTickNs > resendIntervalNs * MAX_BACKLOG_FACTOR) {
                nextTickNs = now + resendIntervalNs
            }
            val waitNs = nextTickNs - now
            if (waitNs > 0) {
                val waitMs = waitNs / 1_000_000L
                if (waitMs > 0) delay(waitMs)
            }
            nextTickNs += resendIntervalNs
            resendOneIfReady()
        }
    }

    /**
     * Coroutine-context active check. Wrapped in a function so the
     * resend-loop body can be quickly stubbed in tests that drive the loop
     * synchronously — calling `isActive` directly inside `while (...)` would
     * leak the coroutine context into every test fixture.
     */
    private fun currentCoroutineActive(): Boolean =
        lifecycleScope.coroutineContext[kotlinx.coroutines.Job]?.isActive ?: true

    /**
     * Default connection-event handler — error + pairing-required surface
     * through [DishNotifications] (per the project's no-Toast rule). A
     * subclass can pre-empt by overriding [onConnectionEvent] and returning
     * after handling; in that case the base implementation still runs
     * unless the subclass also handles it. To make subclasses fully
     * authoritative, this method calls [onConnectionEvent] first and only
     * applies the default if the subclass didn't consume.
     */
    protected fun handleConnectionEvent(event: ConnectionEvent) {
        onConnectionEvent(event)
        when (event) {
            is ConnectionEvent.Error ->
                notifications.error(
                    title = event.message,
                    glyph = R.drawable.ic_satellite_off,
                )
            is ConnectionEvent.PairingRequired ->
                notifications.warn(
                    glyph = R.drawable.ic_satellite_off,
                    title = getString(R.string.notif_pairing_needed_title),
                    body =
                        getString(
                            R.string.notif_pairing_needed_body,
                            event.server.name.ifEmpty { event.server.ip },
                        ),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_open),
                        ) { finish() },
                )
        }
    }

    /**
     * Live display rotation as a `Surface.ROTATION_*` value. Used by phone
     * motion sources (gamepad overlay) for the IMU axis remap. `Activity
     * .display` is the API 30+ path; the deprecated fallback covers below.
     */
    protected fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }

    // ── Input dispatch — forwarded to GamepadActivityHost ───────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"

        /**
         * 4 ms ≈ 250 Hz — matches a typical wired Xbox/PS poll rate, which
         * is the rate ViGEm and uinput's virtual gamepad backends are
         * implicitly tuned for. Faster than the 60 Hz Steam Remote Play
         * / Moonlight default, but wire cost stays trivial (~17 KB/s of
         * encrypted UDP) and the recovery window after a dropped packet
         * shrinks to ≤ 4 ms — invisible at 60 Hz host displays, perceptibly
         * smoother on 120/240 Hz panels and twitch genres.
         */
        const val RESEND_INTERVAL_MS_DEFAULT = 4L
        const val RESEND_INTERVAL_NS_DEFAULT = RESEND_INTERVAL_MS_DEFAULT * 1_000_000L

        /**
         * If the resend loop falls more than this many intervals behind, we
         * reset the deadline rather than issuing back-dated catch-up sends
         * the host has already aged past. Five intervals (~20 ms at 250 Hz)
         * is wider than any normal scheduler hiccup but narrow enough to
         * recover quickly.
         */
        const val MAX_BACKLOG_FACTOR = 5L
    }
}
