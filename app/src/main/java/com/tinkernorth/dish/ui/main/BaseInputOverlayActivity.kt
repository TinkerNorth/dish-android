// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.tinkernorth.dish.ui.common.ContextStringLookup
import com.tinkernorth.dish.ui.common.FoldAwareSession
import com.tinkernorth.dish.ui.common.Posture
import com.tinkernorth.dish.ui.common.ResendPacer
import com.tinkernorth.dish.ui.common.connectionErrorText
import com.tinkernorth.dish.ui.common.hingeInsetsFor
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.resendDeadlineFor
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
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max

abstract class BaseInputOverlayActivity : BaseGamepadHostActivity() {
    @Inject lateinit var hub: ConnectionCoordinator

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var moonlight: com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every input surface is drawn for landscape (pad skin, trackpad, mouse pad), and each
        // subclass declares the orientation config changes so the turn never recreates it.
        // Requested here, before the first layout, rather than fixed in the manifest.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    protected fun installBaseScaffolding() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        installGamepadHost(rootView())
        hideSystemBars()
        installEdgeToEdgeInsets()

        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()

        installFoldAwareness()
        observeConnectionSummary()
        observeSatelliteEvents()
        startResendLoop()
        installLinkGuard()
    }

    // The left and right insets are mirrored so a cutout on one edge does not shift the pad
    // off-centre.
    private fun installEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView()) { view, windowInsets ->
            applyMirroredInsets(view, windowInsets)
        }
    }

    private fun applyMirroredInsets(
        view: View,
        windowInsets: WindowInsetsCompat,
    ): WindowInsetsCompat {
        val insets =
            windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        val mirror = max(insets.left, insets.right)
        view.updatePadding(left = mirror, top = insets.top, right = mirror, bottom = insets.bottom)
        return windowInsets
    }

    private fun observeConnectionSummary() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections
                    .map { conns -> conns.firstOrNull { it.id == connectionId } }
                    .distinctUntilChanged()
                    .collect { onConnectionSummaryChanged(it) }
            }
        }
    }

    private fun observeSatelliteEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect(::handleConnectionEvent)
            }
        }
    }

    private fun startResendLoop() {
        lifecycleScope.launch(resendDispatcher) {
            repeatOnLifecycle(Lifecycle.State.STARTED) { runResendLoop() }
        }
    }

    private var guardCloseJob: Job? = null
    private var linkGuard: OverlayLinkGuardBinding? = null

    private fun installLinkGuard() {
        val guardRoot = rootView().findViewById<View>(R.id.linkGuard) ?: return
        linkGuard = OverlayLinkGuardBinding.bind(guardRoot)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                linkGuardUi().collectLatest(::renderGuardAfterGrace)
            }
        }
    }

    private fun linkGuardUi(): Flow<OverlayGuardUi> =
        combine(
            hub.connections.map { conns -> conns.firstOrNull { it.id == connectionId } },
            hub.bindings.map { it[guardSlotId] },
            slotDeviceStates(),
            networkState.state,
        ) { summary, bound, device, network ->
            overlayGuardFor(summary, bound, connectionId, device, network)
        }.distinctUntilChanged()

    private suspend fun renderGuardAfterGrace(ui: OverlayGuardUi) {
        val guard = linkGuard ?: return
        if (guardWaitsOutBlip(ui.kind)) delay(LINK_GUARD_GRACE_MS)
        renderLinkGuard(guard, ui)
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
            g.tvGuardCountdown.text = String.format(Locale.getDefault(), "%d", graceSec)
        } else if (!ui.autoClose) {
            g.guardCountdownRow.visibility = View.GONE
        }
        if (ui.autoClose && guardCloseJob == null) {
            guardCloseJob = lifecycleScope.launch { countDownAndClose(g) }
        }
    }

    // Terminal states close the overlay themselves, after a countdown the user can read.
    private suspend fun countDownAndClose(g: OverlayLinkGuardBinding) {
        var left = GUARD_AUTO_CLOSE_SEC
        g.guardCountdownRow.visibility = View.VISIBLE
        while (left > 0) {
            g.tvGuardCountdown.text = String.format(Locale.getDefault(), "%d", left)
            delay(GUARD_TICK_MS)
            left--
        }
        finish()
    }

    private fun paintGuardHeader(
        g: OverlayLinkGuardBinding,
        ui: OverlayGuardUi,
    ) {
        val copy = guardCopy(ui)
        g.ivGuardIcon.setImageResource(copy.iconRes)
        g.ivGuardIcon.imageTintList = ColorStateList.valueOf(getColor(copy.colorRes))
        g.tvGuardTitle.setText(copy.titleRes)
        g.tvGuardDetail.text = guardDetailText(copy)
    }

    private fun guardDetailText(copy: GuardCopy): String {
        val arg = copy.detailArg ?: return getString(copy.detailRes)
        return getString(copy.detailRes, arg)
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
            nextTickNs = resendDeadlineFor(nextTickNs, now, resendIntervalNs)
            val waitNs = nextTickNs - now
            if (waitNs > 0) {
                val waitMs = waitNs / NS_PER_MS
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
                    title = connectionErrorText(event.error, ContextStringLookup(this)),
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
    // recreation and re-entry shows the last measurements. A null motionOn means the screen
    // has no motion line.
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
                    rateReadout(
                        screenPeakHz = rates.screenPeakHz,
                        gyroHz = rates.slots[slotId]?.gyroHz ?: 0,
                        hasMotion = motionOn != null,
                        motionOn = on,
                    )
                }.distinctUntilChanged().map(::formatRateReadout).collect { apply(it) }
            }
        }
    }

    private fun formatRateReadout(readout: RateReadout): String {
        val touchPart = getString(R.string.overlay_rate_touch, rateValueText(readout.touch))
        val motion = readout.motion ?: return touchPart
        return getString(
            R.string.binding_func_value,
            touchPart,
            getString(R.string.overlay_rate_motion, rateValueText(motion)),
        )
    }

    private fun rateValueText(reading: RateReading): String =
        when (reading) {
            RateReading.Off -> getString(R.string.binding_state_off)
            RateReading.Pending -> getString(R.string.binding_rate_pending)
            is RateReading.LiveHz -> getString(R.string.binding_rate_hz, reading.hz)
            is RateReading.PeakHz -> getString(R.string.binding_rate_hz_peak, reading.hz)
        }

    protected fun currentRotation(): Int = ContextCompat.getDisplayOrDefault(this).rotation

    private fun installFoldAwareness() {
        val content = rootView().findViewById<View>(R.id.overlayContentFrame) ?: return
        val origTop = content.paddingTop
        val session = FoldAwareSession(this, this)
        observeWhileStarted(session.posture) { posture ->
            applyPostureToContent(content, posture, origTop)
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

    // Sticky immersive on every API level: the platform insets controller from R, the
    // SYSTEM_UI_FLAG_IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION set below it.
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resendThread.quitSafely()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"

        private const val NS_PER_MS = 1_000_000L

        // Tick = the resend SCHEDULER granularity (burst spacing + worst-case
        // single-loss heal time), not a send rate. Real input is event-driven
        // at the full touch sampling rate and never waits on this clock.
        const val RESEND_INTERVAL_MS_DEFAULT = 50L
        const val RESEND_INTERVAL_NS_DEFAULT = RESEND_INTERVAL_MS_DEFAULT * NS_PER_MS

        const val LINK_GUARD_GRACE_MS = 1500L
        const val GUARD_AUTO_CLOSE_SEC = 8
        private const val GUARD_TICK_MS = 1000L
    }
}
