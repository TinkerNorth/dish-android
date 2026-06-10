// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.ui.common.FoldAwareSession
import com.tinkernorth.dish.ui.common.Posture
import com.tinkernorth.dish.ui.common.ResendPacer
import com.tinkernorth.dish.ui.common.hingeInsetsFor
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

abstract class BaseInputOverlayActivity : AppCompatActivity() {
    @Inject lateinit var hub: ConnectionCoordinator

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    protected lateinit var gamepadHost: GamepadActivityHost

    protected var connectionId: String = ""

    // Dedicated URGENT_AUDIO thread so edge-burst resends aren't jittered by the shared Default pool.
    private val resendThread = HandlerThread("dish-resend", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
    private val resendDispatcher = Handler(resendThread.looper).asCoroutineDispatcher()

    // Resend-thread-only (single-threaded Handler dispatcher).
    private val resendPacer = ResendPacer()

    protected abstract fun rootView(): View

    protected abstract val resendIntervalNs: Long

    protected abstract fun resendOneIfReady()

    /** Pacing gate for [resendOneIfReady] implementations — see [ResendPacer]. */
    protected fun resendDue(changed: Boolean): Boolean = resendPacer.resendDue(changed)

    protected open fun onConnectionSummaryChanged(summary: ConnectionSummary?) = Unit

    protected open fun onConnectionEvent(event: ConnectionEvent) = Unit

    protected fun installBaseScaffolding() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Default cutout mode letterboxes content away in landscape, hiding the asymmetry
            // we need to mirror; short-edges surfaces the cutout as a reported inset instead.
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }

        gamepadHost =
            GamepadActivityHost(this, rootView(), wakeState, gamepadRegistry)
                .also { it.install(notifications) }
        hideSystemBars()

        ViewCompat.setOnApplyWindowInsetsListener(rootView()) { v, wi ->
            val ins =
                wi.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            val mirror = max(ins.left, ins.right)
            v.updatePadding(left = mirror, top = ins.top, right = mirror, bottom = ins.bottom)
            wi
        }

        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()

        installFoldAwareness()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections
                    .map { conns -> conns.firstOrNull { it.id == connectionId } }
                    .distinctUntilChanged()
                    .collect { onConnectionSummaryChanged(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect(::handleConnectionEvent)
            }
        }

        // Deadline-paced on the dedicated URGENT_AUDIO thread; main-thread vsync+touch dispatch would jitter the burst ticks.
        lifecycleScope.launch(resendDispatcher) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runResendLoop()
            }
        }
    }

    private suspend fun runResendLoop() {
        var nextTickNs = System.nanoTime() + resendIntervalNs
        while (currentCoroutineActive()) {
            val now = System.nanoTime()
            // Reset deadline on runaway catch-up; don't spam back-dated reports.
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

    private fun currentCoroutineActive(): Boolean = lifecycleScope.coroutineContext[kotlinx.coroutines.Job]?.isActive ?: true

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

    protected fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

    private fun installFoldAwareness() {
        val content = rootView().findViewById<View>(R.id.overlayContentFrame) ?: return
        val origTop = content.paddingTop
        val session = FoldAwareSession(this, this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.posture.collect { posture ->
                    applyPostureToContent(content, posture, origTop)
                }
            }
        }
    }

    private fun applyPostureToContent(
        content: View,
        posture: Posture,
        origTop: Int,
    ) {
        if (!content.isLaidOut) {
            content.post { applyPostureToContent(content, posture, origTop) }
            return
        }
        val insets = posture.hingeInsetsFor(content)
        content.updatePadding(top = origTop + insets.top)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        resendThread.quitSafely()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"

        // Tick = the resend SCHEDULER granularity (burst spacing + worst-case
        // single-loss heal time), not a send rate — real input is event-driven
        // at the full touch sampling rate and never waits on this clock.
        const val RESEND_INTERVAL_MS_DEFAULT = 50L
        const val RESEND_INTERVAL_NS_DEFAULT = RESEND_INTERVAL_MS_DEFAULT * 1_000_000L

        const val MAX_BACKLOG_FACTOR = 5L
    }
}
