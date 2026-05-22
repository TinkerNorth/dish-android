// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
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
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.input.hidToXusb
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityGamepadOverlayBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.sensor.PhoneBatterySource
import com.tinkernorth.dish.source.sensor.PhoneMotionSource
import com.tinkernorth.dish.ui.common.GamepadTouchView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen landscape activity hosting the on-screen touch gamepad.
 *
 * Bound to a single connection id passed via [EXTRA_CONNECTION_ID]; reports
 * are routed to that connection through either the [BluetoothGamepadRegistry]
 * or the matching [com.tinkernorth.dish.source.connection.SatelliteConnection] in
 * the [SatelliteConnectionManager]. Both owners outlive the host activity so
 * the same session is reused on re-entry.
 *
 * Wake-lock state, dim-after-idle, and physical-gamepad pass-through all
 * live in [GamepadActivityHost] — this activity only owns the on-screen
 * touch gamepad and the status pill at the top of the overlay.
 */
@AndroidEntryPoint
class GamepadOverlayActivity :
    AppCompatActivity(),
    GamepadTouchView.Listener {
    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    @Inject lateinit var motionCapability: MotionCapabilityComposer

    private lateinit var binding: ActivityGamepadOverlayBinding
    private lateinit var gamepadHost: GamepadActivityHost
    private var connectionId: String = ""

    /**
     * Most recent gamepad state surfaced by [onGamepadStateChanged]. The touch
     * view's recognizer mutates a single [GamepadTouchView.GamepadState] in
     * place, so this holds the live reference — reading fields here always
     * reflects the latest values. `null` until the user first touches the
     * pad; the periodic-resend loop skips while it is still null.
     *
     * Written on the main thread (touch dispatch) and read from the resend
     * coroutine which now runs on `Dispatchers.Default`, so the reference is
     * `@Volatile` for cross-thread visibility. Per-field reads of the
     * underlying [GamepadTouchView.GamepadState] may briefly see a torn
     * snapshot if the recognizer is mid-update — acceptable for axis values
     * (next tick corrects) and not a correctness hazard.
     */
    @Volatile private var lastReportedState: GamepadTouchView.GamepadState? = null

    /**
     * Phone IMU + battery sources for the on-screen touch controller. Created
     * in [onCreate], streamed only while the activity is resumed (gyro
     * listeners are a measurable battery cost). `motionSource` is a no-op on
     * phones without a gyroscope.
     */
    private lateinit var motionSource: PhoneMotionSource
    private lateinit var batterySource: PhoneBatterySource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamepadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install(notifications) }
        hideSystemBars()

        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()
        binding.gamepadTouchView.listener = this
        binding.gamepadTouchView.usePlayStation = intent.getBooleanExtra(EXTRA_USE_PS_LAYOUT, false)
        binding.dotOverlay.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.colorMuted))
            }
        binding.dotMotion.background =
            GradientDrawable().apply { shape = GradientDrawable.OVAL }
        binding.btnExitGamepad.setOnClickListener { finish() }

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // The IMU axis remap depends on whether `screenOrientation=landscape`
        // resolved to ROTATION_90 or ROTATION_270 on this device. Pass a
        // supplier so PhoneMotionSource re-reads the live rotation each start()
        // rather than baking in a value at onCreate() time.
        motionSource = PhoneMotionSource(sensorManager, rotationSupplier = ::currentRotation)
        // Wire-send only: pushes the virtual controller's MSG_BATTERY to the
        // bound satellite while the overlay is the active input device. The
        // dashboard indicator is fed separately, at process scope, by
        // VirtualBatterySource — so no statusStore is passed here.
        batterySource = PhoneBatterySource(applicationContext)
        // Paint the motion pill once up front: on a phone with no gyroscope
        // this is the only paint that ever runs (start/stop are no-ops), and
        // the "no gyroscope" state must be visible without waiting for resume.
        refreshMotionStatus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect {
                    refreshStatus()
                    // The motion pill depends on connection *kind* (Bluetooth
                    // has no motion channel), so it must repaint when the
                    // bound connection's summary first resolves or changes.
                    refreshMotionStatus()
                }
            }
        }

        // Gate the gyro/accel listeners on the per-slot motion capability:
        // start the source iff the slot is "effective" (gyro present AND
        // bound to a Connected satellite AND the user toggle is on); stop
        // otherwise. Replaces the previous always-on lifecycle in
        // onResume/onPause, which burned battery on Bluetooth-HID
        // connections (where motion can never flow) and ignored the user
        // toggle entirely.
        //
        // The collector runs only while STARTED; repeatOnLifecycle cancels
        // it on STOP, and the launched coroutine stops the source on
        // cancellation so a backgrounded overlay never leaks listeners.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    motionCapability.state.collect { caps ->
                        val effective = caps[VIRTUAL_SLOT_ID]?.effective == true
                        if (effective && !motionSource.isStreaming) {
                            motionSource.start { sample, deltaUs ->
                                satellite.get(connectionId)?.sendMotion(
                                    VIRTUAL_SLOT_ID,
                                    sample.gyroX,
                                    sample.gyroY,
                                    sample.gyroZ,
                                    sample.accelX,
                                    sample.accelY,
                                    sample.accelZ,
                                    deltaUs,
                                )
                            }
                        } else if (!effective && motionSource.isStreaming) {
                            motionSource.stop()
                        }
                        // Repaint after every gate flip so the pill
                        // (STREAMING / PAUSED / NOT_FORWARDED / UNAVAILABLE)
                        // tracks reality without waiting for an unrelated
                        // hub.connections emission.
                        refreshMotionStatus()
                    }
                } finally {
                    // Stop on collector cancellation (STOP / activity destroy)
                    // so a backgrounded overlay never leaks sensor listeners.
                    motionSource.stop()
                }
            }
        }
        // Repaint the motion pill whenever the source's own state flips
        // (Streaming ↔ Stalled in particular). Without this collector the
        // 500 ms stall-detection tick lands inside the source but never
        // reaches the UI — the pill claims STREAMING forever while no
        // samples are actually arriving. Fixes the STALLED-never-repaints
        // bug (the source's tick is the trigger; the activity's collector
        // is what makes it visible).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                motionSource.state.collect { refreshMotionStatus() }
            }
        }

        // Mid-game connection events: with the SatelliteConnectionManager's
        // SharedFlow buffered, errors emitted from the alive-poll's onDead
        // path can now reach this activity. Previously they were silently
        // dropped because the overlay didn't collect.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect(::handleConnectionEvent)
            }
        }
        // Periodic resend of the latest virtual-gamepad state to the satellite.
        //
        // Physical pads route through `gamepadMotionFilter` →
        // `consumePublishIfChanged` (see `satellite_jni.cpp`), which is also
        // change-driven — but the kernel HID poll plus the analog sticks'
        // hardware noise keep producing fresh samples even when the user's
        // thumb is steady, so the wire stays warm and any single dropped UDP
        // packet is recovered within a few ms by the next sample.
        //
        // The touch panel filters sub-pixel jitter, so a finger held still
        // emits ZERO `ACTION_MOVE` events; the listener never re-fires; the
        // wire goes completely silent. If the *last* packet before stillness
        // is the one UDP drops, the host's virtual gamepad sits on stale
        // state until the user moves their finger again — the visible "gap"
        // / "stuck stick" feeling. Resending the latest state every
        // RESEND_INTERVAL_MS replicates the physical-pad's continuous-refresh
        // behaviour and recovers from single-packet loss within one tick.
        //
        // Bluetooth is intentionally excluded: BT-HID has its own polling
        // discipline (the host queries the slave at the negotiated rate),
        // and the bounded native BT dispatch queue would just fill up with
        // redundant reports.
        // Run on Dispatchers.Default — JNI sendReport doesn't need the main
        // thread, and competing with touch dispatch / vsync / layout on
        // Dispatchers.Main.immediate was producing 14 ms tick-interval tails
        // (measured via GpTrace) where the configured rate was 4 ms.
        lifecycleScope.launch(Dispatchers.Default) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Deadline-based pacing — each iteration targets an absolute
                // `nextTickNs` deadline instead of "sleep N ms from now."
                // Without this, work time (sendSatelliteReport + JNI +
                // encrypt + sendto) leaks into the cycle, so a 4 ms config
                // produces ≈ 6 ms cycles → 168 Hz, not the requested 250 Hz.
                // With drift correction, the long-term rate matches the
                // configured interval exactly; a slow tick is followed by a
                // shorter wait, not a permanent slip.
                var nextTickNs = System.nanoTime() + RESEND_INTERVAL_NS
                while (isActive) {
                    val now = System.nanoTime()
                    // Guard against runaway catch-up: if we somehow fell more
                    // than a few intervals behind (process throttled, dispatcher
                    // starved), reset rather than burning CPU spamming back-
                    // dated reports the host has already aged past.
                    if (now - nextTickNs > RESEND_INTERVAL_NS * MAX_BACKLOG_FACTOR) {
                        nextTickNs = now + RESEND_INTERVAL_NS
                    }
                    val waitNs = nextTickNs - now
                    if (waitNs > 0) {
                        val waitMs = waitNs / 1_000_000L
                        if (waitMs > 0) delay(waitMs)
                    }
                    nextTickNs += RESEND_INTERVAL_NS
                    val state = lastReportedState ?: continue
                    val summary = hub.summary(connectionId) ?: continue
                    if (summary.kind != ConnectionKind.SATELLITE) continue
                    if (summary.live != LinkState.Connected) continue
                    sendSatelliteReport(state)
                }
            }
        }
    }

    /**
     * Mid-game connection events: with [SatelliteConnectionManager]'s
     * SharedFlow buffered, errors emitted from the alive-poll's onDead path
     * can now reach this activity. Previously they were silently dropped —
     * the overlay didn't collect events at all. We can't pop a PIN dialog
     * here (full-screen landscape, no dialog host), so [PairingRequired]
     * also routes through the banner with a "return to Connections" action.
     */
    private fun handleConnectionEvent(event: ConnectionEvent) {
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

    override fun onResume() {
        super.onResume()
        // Battery is unconditional — it has its own gating on the connection
        // kind inside the satellite send path, and the cost of a slow battery
        // poll is negligible.
        batterySource.start(lifecycleScope) { level, status ->
            satellite.get(connectionId)?.sendBattery(VIRTUAL_SLOT_ID, level, status)
        }
        // Note: motionSource start/stop is now lifecycle-scoped on the
        // capability flow (see the collector launched in onCreate). The
        // overlay no longer unconditionally registers sensor listeners on
        // Bluetooth-HID connections where motion can't flow, or on slots
        // the user has toggled motion off for — fixing a measurable
        // gyro/accel battery drain that previously ran for nothing.
        refreshMotionStatus()
    }

    override fun onPause() {
        super.onPause()
        batterySource.stop()
        // Source stopped to save battery; reflect "paused" (not "unavailable").
        refreshMotionStatus()
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    private fun refreshStatus() {
        val summary = hub.summary(connectionId)
        val connected = summary?.live == LinkState.Connected
        binding.tvOverlayStatus.text =
            when {
                connected -> summary?.label ?: getString(R.string.overlay_status_streaming)
                summary?.live == LinkState.Connecting -> getString(R.string.chip_status_connecting)
                summary == null -> getString(R.string.overlay_status_unknown)
                else -> getString(R.string.overlay_status_not_connected)
            }
        (binding.dotOverlay.background as? GradientDrawable)?.setColor(
            getColor(if (connected) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    /**
     * Repaint the phone-motion pill. There is no motion on/off toggle in this
     * slice, so the state is purely a function of four facts: whether the
     * phone has a gyroscope ([PhoneMotionSource.isAvailable]), whether the
     * source is currently started ([PhoneMotionSource.isStreaming]), whether
     * the bound connection kind can carry `MSG_MOTION` at all (satellite can,
     * Bluetooth-HID cannot), and whether that connection is actually CONNECTED
     * — a "started" source over a down connection is not really streaming, so
     * the pill must not claim it is. The two states that imply motion is *not*
     * leaving the phone also show a one-line explanation, so a limitation is
     * never mistaken for an off switch. See [MotionIndicatorState].
     */
    private fun refreshMotionStatus() {
        // A null summary (connection not resolved yet) is treated as
        // motion-capable but not-yet-connected: the pill reads "paused" until
        // the kind + liveness resolve, then self-corrects on the next refresh.
        val summary = hub.summary(connectionId)
        val carriesMotion = summary?.kind != ConnectionKind.BLUETOOTH
        val connected = summary?.live == LinkState.Connected
        // The composer is the single source of truth for the user toggle
        // AND the per-type host-sink heuristic — keeps the pill text in
        // sync with the cap-bit and listener-gate decisions made elsewhere.
        val cap = motionCapability.capabilityFor(VIRTUAL_SLOT_ID)
        val state =
            MotionIndicatorState.of(
                isAvailable = motionSource.isAvailable,
                isStreaming = motionSource.isStreaming,
                connectionCarriesMotion = carriesMotion,
                connectionConnected = connected,
                userEnabled = cap.userEnabled,
                hostHasSinkForType = cap.hostHasSinkForType,
                isStalled = motionSource.isStalled,
            )
        binding.tvMotionStatus.setText(state.labelRes)
        (binding.dotMotion.background as? GradientDrawable)?.setColor(getColor(state.dotColorRes))
        binding.tvMotionDetail.visibility = if (state.hasDetail) View.VISIBLE else View.GONE
        when (state) {
            MotionIndicatorState.UNAVAILABLE ->
                binding.tvMotionDetail.setText(R.string.motion_unavailable_detail)
            MotionIndicatorState.NOT_FORWARDED ->
                binding.tvMotionDetail.setText(R.string.motion_not_forwarded_detail)
            MotionIndicatorState.STALLED ->
                binding.tvMotionDetail.setText(R.string.motion_stalled_detail)
            MotionIndicatorState.USER_DISABLED ->
                binding.tvMotionDetail.setText(R.string.motion_user_disabled_detail)
            MotionIndicatorState.NO_HOST_SINK ->
                binding.tvMotionDetail.setText(R.string.motion_no_host_sink_detail)
            else -> Unit
        }
    }

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        // Stored before the connection-status gate so input captured while
        // the session is still Linking is replayed by the resend loop the
        // moment it flips to Connected.
        lastReportedState = state
        val summary = hub.summary(connectionId) ?: return
        if (summary.live != LinkState.Connected) return
        when (summary.kind) {
            ConnectionKind.BLUETOOTH -> {
                val report =
                    btRegistry.buildReport(
                        connectionId,
                        state.buttons,
                        state.hatSwitch,
                        state.leftX,
                        state.leftY,
                        state.rightX,
                        state.rightY,
                        state.leftTrigger,
                        state.rightTrigger,
                    ) ?: return
                btRegistry.sendReport(connectionId, report)
            }
            ConnectionKind.SATELLITE -> sendSatelliteReport(state)
        }
    }

    /**
     * Emit one MSG_GAMEPAD_DATA report to the bound satellite. The touch
     * view emits HID-layout button bits plus a separate hat-switch; the
     * satellite path wants an XUSB `wButtons` with the d-pad folded back
     * into the low nibble. Shared between the touch-driven [onGamepadStateChanged]
     * and the periodic resend loop in [onCreate].
     */
    private fun sendSatelliteReport(state: GamepadTouchView.GamepadState) {
        val wButtons = hidToXusb(state.buttons, state.hatSwitch)
        satellite.get(connectionId)?.sendReport(
            VIRTUAL_SLOT_ID,
            wButtons,
            state.leftTrigger,
            state.rightTrigger,
            state.leftX.toInt(),
            state.leftY.toInt(),
            state.rightX.toInt(),
            state.rightY.toInt(),
        )
    }

    /**
     * Live display rotation as a `Surface.ROTATION_*` value, for the IMU axis
     * remap in [PhoneMotionSource]. `Activity.display` is the API 30+ path;
     * `windowManager.defaultDisplay` is the deprecated fallback below R.
     * Defaults to [Surface.ROTATION_0] if no display is attached.
     */
    private fun currentRotation(): Int =
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
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DISPATCH — forwarded to GamepadActivityHost
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
        const val EXTRA_USE_PS_LAYOUT = "extra_use_ps_layout"

        /**
         * Interval between periodic resends of the last virtual-gamepad
         * state to a satellite. 4 ms ≈ 250 Hz matches the polling rate of a
         * typical wired Xbox/PS controller — the rate the host's virtual
         * gamepad backend (ViGEm / equivalent) is implicitly tuned for.
         * Faster than the 60 Hz Steam Remote Play / Moonlight default, but
         * the wire cost is still trivial (~17 KB/s of encrypted UDP) and the
         * recovery window after a dropped packet shrinks to ≤ 4 ms instead
         * of ≤ 16 ms — invisible at 60 Hz host displays, perceptibly
         * smoother on 120/240 Hz panels and twitch genres.
         *
         * The resend loop uses [RESEND_INTERVAL_NS] for deadline arithmetic;
         * this ms value is preserved as the human-facing knob.
         */
        private const val RESEND_INTERVAL_MS = 4L
        private const val RESEND_INTERVAL_NS = RESEND_INTERVAL_MS * 1_000_000L

        /**
         * If the resend loop falls more than this many intervals behind
         * `nextTickNs` (e.g. because the dispatcher was starved or the
         * process was throttled), we reset the deadline to `now + INTERVAL`
         * instead of issuing back-dated catch-up sends. The host has already
         * aged past those states; sending them adds wire chatter without
         * benefit. Five intervals (~20 ms at 250 Hz) is wider than any
         * normal scheduler hiccup but narrow enough to recover quickly.
         */
        private const val MAX_BACKLOG_FACTOR = 5L
    }
}
