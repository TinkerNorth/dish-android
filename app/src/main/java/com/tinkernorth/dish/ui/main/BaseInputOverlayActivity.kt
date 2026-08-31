// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.OverlayLinkGuardBinding
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.system.NetworkStateObserver
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.FoldAwareSession
import com.tinkernorth.dish.ui.common.Posture
import com.tinkernorth.dish.ui.common.ResendPacer
import com.tinkernorth.dish.ui.common.hingeInsetsFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

abstract class BaseInputOverlayActivity : BaseGamepadHostActivity() {
    @Inject lateinit var hub: ConnectionCoordinator

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var inputRateStore: InputRateStore

    @Inject lateinit var networkState: NetworkStateObserver

    protected var connectionId: String = ""

    // Dedicated URGENT_AUDIO thread so edge-burst resends aren't jittered by the shared Default pool.
    private val resendThread = HandlerThread("dish-resend", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
    private val resendDispatcher = Handler(resendThread.looper).asCoroutineDispatcher()

    // Resend-thread-only (single-threaded Handler dispatcher).
    private val resendPacer = ResendPacer()

    protected abstract fun rootView(): View

    protected abstract val resendIntervalNs: Long

    protected abstract fun resendOneIfReady()

    protected fun resendDue(changed: Boolean): Boolean = resendPacer.resendDue(changed)

    protected open fun onConnectionSummaryChanged(summary: ConnectionSummary?) = Unit

    protected open fun onConnectionEvent(event: ConnectionEvent) = Unit

    // The slot this overlay drives; the link guard watches its binding and its controller.
    protected open val guardSlotId: String get() = VIRTUAL_SLOT_ID

    // Physical-controller presence behind the slot; null for slots with no controller.
    protected open fun slotDeviceStates(): Flow<SlotDeviceState?> = flowOf(null)

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

        installGamepadHost(rootView())
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

        lifecycleScope.launch(resendDispatcher) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runResendLoop()
            }
        }

        installLinkGuard()
    }

    private var guardCloseJob: Job? = null

    private fun installLinkGuard() {
        val guardRoot = rootView().findViewById<View>(R.id.linkGuard) ?: return
        val guard = OverlayLinkGuardBinding.bind(guardRoot)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    hub.connections.map { conns -> conns.firstOrNull { it.id == connectionId } },
                    hub.bindings.map { it[guardSlotId] },
                    slotDeviceStates(),
                    networkState.state,
                ) { summary, bound, device, network ->
                    overlayGuardFor(summary, bound, connectionId, device, network)
                }.distinctUntilChanged().collectLatest { ui ->
                    // A link blip settles before the scrim appears; recovery paints instantly.
                    if (ui.kind == GuardKind.RECONNECTING || ui.kind == GuardKind.HOST_LOST) {
                        delay(LINK_GUARD_GRACE_MS)
                    }
                    renderLinkGuard(guard, ui)
                }
            }
        }
    }

    private fun renderLinkGuard(
        g: OverlayLinkGuardBinding,
        ui: OverlayGuardUi,
    ) {
        if (!ui.autoClose) {
            guardCloseJob?.cancel()
            guardCloseJob = null
        }
        g.root.visibility = if (ui.kind == GuardKind.NONE) View.GONE else View.VISIBLE
        if (ui.kind == GuardKind.NONE) return
        paintGuardHeader(g, ui)
        paintGuardActions(g, ui)
        g.pbGuardSpinner.visibility = if (ui.kind == GuardKind.RECONNECTING) View.VISIBLE else View.GONE
        val graceSec = ui.countdownSec
        if (graceSec != null) {
            g.guardCountdownRow.visibility = View.VISIBLE
            g.tvGuardCountdown.text = graceSec.toString()
        } else if (!ui.autoClose) {
            g.guardCountdownRow.visibility = View.GONE
        }
        if (ui.autoClose && guardCloseJob == null) {
            guardCloseJob =
                lifecycleScope.launch {
                    var left = GUARD_AUTO_CLOSE_SEC
                    g.guardCountdownRow.visibility = View.VISIBLE
                    while (left > 0) {
                        g.tvGuardCountdown.text = left.toString()
                        delay(1000L)
                        left--
                    }
                    finish()
                }
        }
    }

    private fun paintGuardHeader(
        g: OverlayLinkGuardBinding,
        ui: OverlayGuardUi,
    ) {
        val (icon, color) =
            when (ui.kind) {
                GuardKind.HOST_LOST, GuardKind.GONE -> R.drawable.ic_error to R.color.colorError
                GuardKind.RECONNECTING -> R.drawable.ic_refresh to R.color.colorPrimary
                GuardKind.UNBOUND -> R.drawable.ic_link_off to R.color.colorWarning
                else -> R.drawable.ic_gamepad to R.color.colorWarning
            }
        g.ivGuardIcon.setImageResource(icon)
        g.ivGuardIcon.imageTintList = ColorStateList.valueOf(getColor(color))
        g.tvGuardTitle.setText(guardTitleRes(ui.kind))
        g.tvGuardDetail.text = guardDetailText(ui)
    }

    private fun guardTitleRes(kind: GuardKind): Int =
        when (kind) {
            GuardKind.HOST_LOST -> R.string.binding_edge_host_lost_title
            GuardKind.RECONNECTING -> R.string.chip_status_connecting
            GuardKind.UNPLUGGED, GuardKind.DEPARTED -> R.string.binding_edge_input_lost_title
            GuardKind.UNBOUND -> R.string.overlay_guard_unbound_title
            else -> R.string.overlay_guard_gone_title
        }

    private fun guardDetailText(ui: OverlayGuardUi): String =
        when (ui.kind) {
            GuardKind.HOST_LOST, GuardKind.RECONNECTING ->
                when (ui.detail) {
                    GuardDetail.WIFI_DOWN -> getString(R.string.overlay_guard_wifi_detail)
                    GuardDetail.BLUETOOTH_HOST -> getString(R.string.overlay_guard_bt_detail)
                    GuardDetail.MOONLIGHT_SESSION -> getString(R.string.overlay_guard_ml_detail)
                    GuardDetail.GENERIC -> getString(R.string.binding_edge_host_lost_detail, ui.hostLabel)
                }
            GuardKind.UNPLUGGED -> getString(R.string.overlay_guard_replug_detail)
            GuardKind.DEPARTED -> getString(R.string.overlay_guard_departed_detail)
            GuardKind.UNBOUND -> getString(R.string.overlay_guard_unbound_detail, ui.hostLabel)
            else -> getString(R.string.overlay_guard_gone_detail)
        }

    private fun paintGuardActions(
        g: OverlayLinkGuardBinding,
        ui: OverlayGuardUi,
    ) {
        if (ui.showReconnect) {
            g.btnGuardPrimary.visibility = View.VISIBLE
            g.btnGuardPrimary.setIconResource(R.drawable.ic_refresh)
            g.btnGuardPrimary.setText(R.string.binding_edge_action_reconnect)
            g.btnGuardPrimary.setOnClickListener { hub.autoReconnectAll() }
        } else if (ui.autoClose) {
            g.btnGuardPrimary.visibility = View.VISIBLE
            g.btnGuardPrimary.setIconResource(R.drawable.ic_link_off)
            g.btnGuardPrimary.setText(R.string.action_close)
            g.btnGuardPrimary.setOnClickListener { finish() }
        } else {
            g.btnGuardPrimary.visibility = View.GONE
        }
        g.btnGuardSecondary.visibility = if (ui.autoClose) View.GONE else View.VISIBLE
        g.btnGuardSecondary.setText(R.string.action_close)
        g.btnGuardSecondary.setOnClickListener { finish() }
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

    // The store owns the trackers and the low-power freeze, so the readout survives activity
    // recreation and re-entry shows the last measurements. motionOn gates the motion line:
    // the source may stream while motion is user-facing off, and the readout must agree with
    // the motion indicator, not the raw sample flow; null means the screen has no motion line.
    protected fun installRateReadout(
        slotId: String,
        motionOn: Flow<Boolean>?,
        apply: (String) -> Unit,
    ) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    inputRateStore.state,
                    motionOn ?: flowOf(false),
                ) { rates, on ->
                    formatRateReadout(
                        screenPeakHz = rates.screenPeakHz,
                        gyroHz = rates.slots[slotId]?.gyroHz ?: 0,
                        hasMotion = motionOn != null,
                        motionOn = on,
                    )
                }.distinctUntilChanged().collect { apply(it) }
            }
        }
    }

    private fun formatRateReadout(
        screenPeakHz: Int,
        gyroHz: Int,
        hasMotion: Boolean,
        motionOn: Boolean,
    ): String {
        val touchValue =
            if (screenPeakHz > 0) {
                getString(R.string.binding_rate_hz_peak, screenPeakHz)
            } else {
                getString(R.string.binding_rate_pending)
            }
        val touchPart = getString(R.string.overlay_rate_touch, touchValue)
        if (!hasMotion) return touchPart
        val motionValue =
            when {
                !motionOn -> getString(R.string.binding_state_off)
                gyroHz > 0 -> getString(R.string.binding_rate_hz, gyroHz)
                else -> getString(R.string.binding_rate_pending)
            }
        return getString(
            R.string.binding_func_value,
            touchPart,
            getString(R.string.overlay_rate_motion, motionValue),
        )
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

    override fun onDestroy() {
        super.onDestroy()
        resendThread.quitSafely()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"

        // Tick = the resend SCHEDULER granularity (burst spacing + worst-case
        // single-loss heal time), not a send rate. Real input is event-driven
        // at the full touch sampling rate and never waits on this clock.
        const val RESEND_INTERVAL_MS_DEFAULT = 50L
        const val RESEND_INTERVAL_NS_DEFAULT = RESEND_INTERVAL_MS_DEFAULT * 1_000_000L

        const val MAX_BACKLOG_FACTOR = 5L

        const val LINK_GUARD_GRACE_MS = 1500L
        const val GUARD_AUTO_CLOSE_SEC = 8
    }
}
