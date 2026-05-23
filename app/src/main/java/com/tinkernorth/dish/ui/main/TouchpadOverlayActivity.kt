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
import com.tinkernorth.dish.ui.common.TouchpadPadCoordinator
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import com.tinkernorth.dish.ui.common.setupDishToolbar
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
 * sender — it doesn't decide the routing mode, only labels each pad with
 * what its touches will do downstream.
 *
 * **Two pads, one wire owner.** The overlay mounts two
 * [TouchpadSurfaceView]s side by side:
 *   - **Click + Move pad** (`touchpadClickPad`) — `clickWhenTouched = true`.
 *     A contact here asserts the touchpad's click button alongside the
 *     finger position. In MOUSE mode this is a held left-click — drag for
 *     text selection or drag-and-drop.
 *   - **Move pad** (`touchpadMovePad`) — `clickWhenTouched = false`. A
 *     contact here only updates finger position; the click button stays
 *     low. The plain "move the cursor without selecting anything" surface.
 *
 * Mutual exclusion: when one pad's first finger lands, the other goes to
 * `accepting = false` (touches ignored + dim alpha). On the last finger
 * lift the lock releases. Necessary because the MSG_TOUCHPAD wire format
 * carries one finger-0 per packet — and the satellite's mouse mode only
 * reads finger 0 — so two simultaneously emitting pads would race to
 * overwrite each other's coordinates.
 *
 * The selected routing mode (Pad vs Mouse) is passed in via
 * [EXTRA_TOUCHPAD_MODE] and only affects what each pad's touches DO at
 * the receiver (Pad → DS4 virtual touchpad; Mouse → host pointer). The
 * wire payload is identical regardless. Off mode is intentionally
 * unreachable here — if the user picks Off, the "Open Touchpad" button
 * is hidden on the connection card and this activity never launches.
 */
@AndroidEntryPoint
class TouchpadOverlayActivity : BaseInputOverlayActivity() {
    private lateinit var binding: ActivityTouchpadOverlayBinding

    /**
     * Most recent touchpad state surfaced by whichever pad is currently
     * active. The surface views mutate one [TouchpadSurfaceView.TouchpadState]
     * instance each in place, so this holds the live reference to whichever
     * pad currently owns the wire. `null` until the user first touches a
     * pad — the periodic resend loop skips while null, exactly as the
     * gamepad overlay does for its [GamepadOverlayActivity.lastReportedState].
     *
     * Written on the main thread (touch dispatch) and read on
     * `Dispatchers.Default` from the base resend loop, so the reference is
     * `@Volatile` for cross-thread visibility. Per-field reads may briefly
     * see a torn snapshot if the recognizer is mid-update — acceptable for
     * touchpad coordinates (the next tick corrects).
     */
    @Volatile private var lastReportedState: TouchpadSurfaceView.TouchpadState? = null

    /**
     * The slot whose controllerIndex the touchpad samples ride under. Defaulted
     * to [VIRTUAL_SLOT_ID] so a launch without [EXTRA_SLOT_ID] (e.g. the prior
     * virtual-only flow) still works; physical-slot launches pass the slot id
     * so the receiver routes touchpad bytes through the right virtual device.
     */
    private var slotId: String = VIRTUAL_SLOT_ID

    /**
     * Mutual-exclusion lock for the Click + Move pads. Whichever pad's
     * first finger lands wins ownership; the other is locked
     * (`accepting=false`, dimmed) until the owner releases. Extracted
     * into [TouchpadPadCoordinator] so the lock semantics can be
     * unit-tested without instantiating the surface view (which needs a
     * real [android.content.Context]).
     */
    private val padCoordinator = TouchpadPadCoordinator<TouchpadSurfaceView>()

    override fun rootView(): View = binding.root

    override val resendIntervalNs: Long = BaseInputOverlayActivity.RESEND_INTERVAL_NS_DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installBaseScaffolding()
        slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: VIRTUAL_SLOT_ID

        // Shared toolbar: left-chevron back wired to finish(), page title
        // on the right of the chevron. Same `setupDishToolbar` plumbing
        // Connections + Settings use, so the chevron's tap behaviour,
        // ripple, and content description all match the rest of the app
        // for free.
        setupDishToolbar(binding.overlayToolbar)
        binding.overlayToolbar.setTitle(R.string.overlay_title_touchpad)

        // Click + Move pad: any contact asserts the click button (mouse
        // button 1 in MOUSE mode, DS4 trackpad-click in PAD mode).
        bindPad(
            pad = binding.touchpadClickPad,
            other = binding.touchpadMovePad,
            clickWhenTouched = true,
            labelRes = R.string.touchpad_pad_click_label,
            hintRes = R.string.touchpad_pad_click_hint,
        )
        // Move pad: position-only, button stays low. The "plain cursor
        // navigation" surface that doesn't accidentally select text.
        bindPad(
            pad = binding.touchpadMovePad,
            other = binding.touchpadClickPad,
            clickWhenTouched = false,
            labelRes = R.string.touchpad_pad_move_label,
            hintRes = R.string.touchpad_pad_move_hint,
        )
    }

    /**
     * Wire one [TouchpadSurfaceView] into the activity: set its mode +
     * labels, attach a listener that funnels touch state into
     * [lastReportedState] AND drives mutual exclusion against the [other]
     * pad. Pulled into a helper so the two pads' setup is symmetric and
     * any change applies to both.
     */
    private fun bindPad(
        pad: TouchpadSurfaceView,
        other: TouchpadSurfaceView,
        clickWhenTouched: Boolean,
        labelRes: Int,
        hintRes: Int,
    ) {
        pad.clickWhenTouched = clickWhenTouched
        pad.label = getString(labelRes)
        pad.hint = getString(hintRes)
        pad.listener =
            object : TouchpadSurfaceView.Listener {
                override fun onTouchpadStateChanged(state: TouchpadSurfaceView.TouchpadState) {
                    // Only the active pad (or both pads when neither has
                    // ownership yet) writes through. A locked pad's
                    // accepting=false setter already flushed its lift
                    // emit, so by the time mayWrite returns false the
                    // locked pad has nothing in flight worth forwarding
                    // — but the check keeps the ownership contract
                    // explicit instead of relying on side-effects.
                    if (!padCoordinator.mayWrite(pad)) return
                    lastReportedState = state
                    val summary = hub.summary(connectionId) ?: return
                    if (summary.live != LinkState.Connected) return
                    if (summary.kind != ConnectionKind.SATELLITE) return
                    sendSatelliteTouchpadReport(state)
                }

                override fun onTouchActivityChanged(active: Boolean) {
                    if (active) {
                        // First-contact gate: claim ownership and lock
                        // out the other pad. If another pad already owns
                        // (two ACTION_DOWNs on the same input frame —
                        // vanishingly rare given the serialised input
                        // dispatcher), the coordinator returns false and
                        // we leave this pad untouched.
                        if (padCoordinator.onTouchStart(pad)) {
                            other.accepting = false
                        }
                    } else {
                        // Last finger lifted on this pad. If we were the
                        // owner, the coordinator releases the lock and
                        // we unlock the other pad so either can claim
                        // ownership on the next touch.
                        if (padCoordinator.onTouchEnd(pad)) {
                            other.accepting = true
                        }
                    }
                }
            }
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
        binding.statusPillTouchpad.statusPillLabel.text =
            when {
                // `connected` true implies summary is non-null
                // (summary?.live == LinkState.Connected can't match a
                // null summary), so the compiler smart-casts the
                // receiver — no safe-call needed.
                connected -> summary.label
                summary?.live == LinkState.Connecting -> getString(R.string.chip_status_connecting)
                summary == null -> getString(R.string.overlay_status_unknown)
                else -> getString(R.string.overlay_status_not_connected)
            }
        (binding.statusPillTouchpad.statusPillDot.background as? GradientDrawable)?.setColor(
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
            slotId,
            state.finger0Active,
            state.finger1Active,
            state.buttonPressed,
            state.finger0TrackingId,
            state.finger0X,
            state.finger0Y,
            state.finger1TrackingId,
            state.finger1X,
            state.finger1Y,
            state.eventTimeMs,
        )
    }

    companion object {
        /**
         * The user-selected touchpad routing mode at launch time — `"ds4"`
         * or `"mouse"`. Kept for future per-mode visual treatment (e.g.
         * different finger-dot tint when the receiver will synthesise a
         * pointer vs feed a DS4 surface). The `"off"` value is intentionally
         * unsupported here because the "Open Touchpad" button is hidden in
         * Off mode and this activity is never launched in that state.
         */
        const val EXTRA_TOUCHPAD_MODE = "extra_touchpad_mode"

        /**
         * Slot id whose controllerIndex the wire payload rides under.
         * Defaults to [VIRTUAL_SLOT_ID] when absent — keeps the prior
         * virtual-only launch path working unchanged. A physical-slot
         * launch passes the physical InputDevice id so the receiver
         * routes touchpad bytes through the virtual device registered for
         * that slot.
         */
        const val EXTRA_SLOT_ID = "extra_slot_id"

        /**
         * Re-export of [BaseInputOverlayActivity.EXTRA_CONNECTION_ID] for
         * symmetry with [GamepadOverlayActivity.EXTRA_CONNECTION_ID] — call
         * sites picking either overlay can use the same qualified prefix.
         */
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID
    }
}
