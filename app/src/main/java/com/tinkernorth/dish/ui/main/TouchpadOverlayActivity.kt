// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.databinding.ActivityTouchpadOverlayBinding
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen landscape overlay hosting the on-screen virtual **touchpad**
 * — sibling to [GamepadOverlayActivity]. Both extend
 * [BaseInputOverlayActivity] for the wake-lock host, the system-bar hide,
 * the 250 Hz deadline-paced resend loop, the connection-summary
 * subscription, and the connection-event handling.
 *
 * Bound to a single connection id via
 * [BaseInputOverlayActivity.EXTRA_CONNECTION_ID]; touchpad samples are
 * routed to that connection's [com.tinkernorth.dish.source.connection.SatelliteConnection]
 * through [ControllerRepository.sendTouchpad]. The activity is purely a
 * sender — it doesn't decide the routing mode, only labels the surface
 * with the mode the user picked on the connection card so the visual
 * matches the wire behaviour.
 *
 * The activity's selected mode (Pad vs Mouse) is passed in via
 * [EXTRA_TOUCHPAD_MODE] and changes only the surface label + style; the
 * wire payload is identical regardless. Off mode is intentionally
 * unreachable here — if the user picks Off, the "Open Touchpad" button is
 * hidden on the connection card and this activity never launches.
 */
@AndroidEntryPoint
class TouchpadOverlayActivity :
    BaseInputOverlayActivity(),
    TouchpadSurfaceView.Listener {
    private lateinit var binding: ActivityTouchpadOverlayBinding

    /**
     * Most recent touchpad state surfaced by [onTouchpadStateChanged]. The
     * surface view's recogniser mutates one instance in place, so this is
     * the live reference. `null` until the user first touches the surface —
     * the periodic resend loop skips while null, exactly as the gamepad
     * overlay does for its [GamepadOverlayActivity.lastReportedState].
     *
     * Written on the main thread (touch dispatch) and read on
     * `Dispatchers.Default` from the base resend loop, so the reference is
     * `@Volatile` for cross-thread visibility. Per-field reads may briefly
     * see a torn snapshot if the recognizer is mid-update — acceptable for
     * touchpad coordinates (the next tick corrects).
     */
    @Volatile private var lastReportedState: TouchpadSurfaceView.TouchpadState? = null

    override fun rootView(): View = binding.root

    override val resendIntervalNs: Long = BaseInputOverlayActivity.RESEND_INTERVAL_NS_DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installBaseScaffolding()

        // Status pill — same shape as the gamepad overlay's connection chip,
        // painted on every connection-summary emission via the base's hook.
        binding.dotTouchpadOverlay.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.colorMuted))
            }
        binding.btnExitTouchpad.setOnClickListener { finish() }

        // Apply the user-picked mode to the surface — purely visual; the
        // wire payload is mode-independent (the satellite routes based on
        // its per-device touchpadMode). The "Mouse" launch path also gates
        // out at the card level when the server doesn't advertise mouse
        // support, so this activity never enters Mouse mode against a
        // backend that can't honour it.
        val modeStr = intent.getStringExtra(EXTRA_TOUCHPAD_MODE).orEmpty()
        binding.touchpadSurfaceView.mode =
            when (modeStr) {
                TouchpadModeValue.MOUSE -> TouchpadSurfaceView.Mode.Mouse
                else -> TouchpadSurfaceView.Mode.Pad
            }
        binding.touchpadSurfaceView.listener = this
    }

    override fun onTouchpadStateChanged(state: TouchpadSurfaceView.TouchpadState) {
        // Cache for the resend loop, then satisfy the change-driven send for
        // low-latency response on the very first touch. Same shape as
        // GamepadOverlayActivity.onGamepadStateChanged.
        lastReportedState = state
        val summary = hub.summary(connectionId) ?: return
        if (summary.live != LinkState.Connected) return
        if (summary.kind != ConnectionKind.SATELLITE) return
        sendSatelliteTouchpadReport(state)
    }

    override fun resendOneIfReady() {
        val state = lastReportedState ?: return
        val summary = hub.summary(connectionId) ?: return
        if (summary.kind != ConnectionKind.SATELLITE) return
        if (summary.live != LinkState.Connected) return
        sendSatelliteTouchpadReport(state)
    }

    override fun onConnectionSummaryChanged(summary: ConnectionSummary?) {
        val connected = summary?.live == LinkState.Connected
        binding.tvTouchpadOverlayStatus.text =
            when {
                // `connected` true implies summary is non-null (`summary?.live ==
                // LinkState.Connected` can't match a null summary), so the
                // compiler smart-casts the receiver — no safe-call needed.
                connected -> summary.label
                summary?.live == LinkState.Connecting -> getString(R.string.chip_status_connecting)
                summary == null -> getString(R.string.overlay_status_unknown)
                else -> getString(R.string.overlay_status_not_connected)
            }
        (binding.dotTouchpadOverlay.background as? GradientDrawable)?.setColor(
            getColor(if (connected) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    /**
     * Emit one MSG_TOUCHPAD packet to the bound satellite. The satellite
     * routes by its own per-device touchpad mode (Off drops it, Pad feeds
     * the virtual DS4 trackpad, Mouse synthesises desktop pointer motion);
     * this activity is mode-agnostic on the wire.
     *
     * Resolves the live connection by id each call so re-connects after an
     * alive-poll death pick up the new session handle without restarting
     * the activity. Same registered-gate + atomic snapshot discipline as
     * the gamepad path (`SatelliteConnection.sendTouchpad` handles both).
     */
    private fun sendSatelliteTouchpadReport(state: TouchpadSurfaceView.TouchpadState) {
        satellite.get(connectionId)?.sendTouchpad(
            VIRTUAL_SLOT_ID,
            state.finger0Active,
            state.finger1Active,
            state.buttonPressed,
            state.finger0TrackingId,
            state.finger0X,
            state.finger0Y,
            state.finger1TrackingId,
            state.finger1X,
            state.finger1Y,
        )
    }

    companion object {
        /**
         * The user-selected touchpad mode at launch time — `"ds4"` or
         * `"mouse"`. Drives only the surface visual; the wire is the same
         * either way. The `"off"` value is intentionally unsupported here
         * because the "Open Touchpad" button is hidden in Off mode and this
         * activity is never launched in that state.
         */
        const val EXTRA_TOUCHPAD_MODE = "extra_touchpad_mode"

        /**
         * Re-export of [BaseInputOverlayActivity.EXTRA_CONNECTION_ID] for
         * symmetry with [GamepadOverlayActivity.EXTRA_CONNECTION_ID] — call
         * sites picking either overlay can use the same qualified prefix.
         */
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID
    }
}
