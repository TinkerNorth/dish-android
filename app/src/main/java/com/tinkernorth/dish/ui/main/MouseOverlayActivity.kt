// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.databinding.ActivityMouseOverlayBasicBinding
import com.tinkernorth.dish.databinding.ActivityMouseOverlayBinding
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.ui.common.HoldButtonView
import com.tinkernorth.dish.ui.common.ScrollStripView
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import com.tinkernorth.dish.ui.common.paintConnectionMenuItem
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.showConnectionDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MouseOverlayActivity : BaseInputOverlayActivity() {
    @Inject lateinit var mouseSurfaceStore: MouseSurfaceStore

    @Inject lateinit var hostFeaturesStore: SatelliteHostFeaturesStore

    // The two layouts share every view but the extended column; which one inflates is
    // decided by what the satellite advertised, so the screen never shows a right
    // button or a scroll wheel the receiver would ignore.
    private class MouseViews(
        val root: View,
        val toolbar: MaterialToolbar,
        val left: HoldButtonView,
        val movePad: TouchpadSurfaceView,
        val right: HoldButtonView?,
        val strip: ScrollStripView?,
    )

    private lateinit var views: MouseViews

    private data class MouseWireState(
        val fingers: TouchpadSurfaceView.TouchpadState,
        val leftHeld: Boolean,
        val rightHeld: Boolean,
        val middleHeld: Boolean,
    )

    // @Volatile for main-thread write / resend-thread read.
    @Volatile private var lastReported: MouseWireState? = null

    private var slotId: String = VIRTUAL_SLOT_ID
    private var leftHeld = false
    private var rightHeld = false
    private var middleHeld = false

    // Moonlight edge tracking: what the host was last told, plus the finger anchor
    // and the sub-pixel remainders the relative moves accumulate against.
    private var mlLeftSent = false
    private var mlRightSent = false
    private var mlMiddleSent = false
    private var mlTrackingId = Int.MIN_VALUE
    private var mlLastX = 0
    private var mlLastY = 0
    private var mlRemX = 0f
    private var mlRemY = 0f

    private var optionsMenu: Menu? = null
    private var currentSummary: ConnectionSummary? = null

    override fun rootView(): View = views.root

    override val resendIntervalNs: Long = BaseInputOverlayActivity.RESEND_INTERVAL_NS_DEFAULT

    override val guardSlotId: String get() = slotId

    override fun slotDeviceStates(): Flow<SlotDeviceState?> {
        val deviceId = slotId.toIntOrNull() ?: return flowOf(null)
        return gamepadRegistry.devices.map { devices ->
            val device = devices[deviceId] ?: return@map SlotDeviceState(present = false)
            SlotDeviceState(
                present = true,
                disconnectingSecLeft = device.disconnectingTimeLeftSec,
                transitioning = device.transitioning,
                needsReplug = device.needsReplug,
            )
        }
    }

    // Resend-thread-only (single-threaded Handler dispatcher).
    private var lastResentSnapshot: MouseWireState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: VIRTUAL_SLOT_ID
        val hostId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()
        // Moonlight carries buttons and scroll natively; a satellite only past the
        // pointer-frame protocol version does. The store holds the live negotiated
        // version (session open writes it), so this read follows the real session.
        val extended =
            hub.summary(hostId)?.kind == ConnectionKind.MOONLIGHT ||
                hostFeaturesStore.featuresFor(hostId)?.extendedMouse == true
        views = inflateFor(extended)
        setContentView(views.root)
        installBaseScaffolding()

        setupDishToolbar(views.toolbar)
        views.toolbar.setTitle(R.string.overlay_title_mouse)
        installRateReadout(
            slotId = slotId,
            motionOn = null,
        ) { views.toolbar.subtitle = it }

        views.left.onHeldChanged = { held ->
            leftHeld = held
            report(latestFingers())
        }
        views.right?.onHeldChanged = { held ->
            rightHeld = held
            report(latestFingers())
        }
        views.strip?.onScroll = { notches ->
            report(latestFingers(), scrollNotches = notches)
        }
        views.strip?.onMiddleTap = { pulseMiddleClick() }

        views.movePad.clickWhenTouched = false
        views.movePad.label = getString(R.string.touchpad_pad_move_label)
        views.movePad.hint = getString(R.string.touchpad_pad_move_hint)
        views.movePad.listener =
            object : TouchpadSurfaceView.Listener {
                override fun onTouchpadStateChanged(state: TouchpadSurfaceView.TouchpadState) {
                    report(state)
                }
            }
    }

    private fun inflateFor(extended: Boolean): MouseViews =
        if (extended) {
            val b = ActivityMouseOverlayBinding.inflate(layoutInflater)
            MouseViews(
                root = b.root,
                toolbar = b.overlayToolbar,
                left = b.btnMouseLeft,
                movePad = b.mouseMovePad,
                right = b.btnMouseRight,
                strip = b.scrollStrip,
            )
        } else {
            val b = ActivityMouseOverlayBasicBinding.inflate(layoutInflater)
            MouseViews(
                root = b.root,
                toolbar = b.overlayToolbar,
                left = b.btnMouseLeft,
                movePad = b.mouseMovePad,
                right = null,
                strip = null,
            )
        }

    // The store flips this slot's wire routing to mouse for exactly as long as the
    // surface is on screen; the descriptor converge rides the store's emission.
    override fun onStart() {
        super.onStart()
        mouseSurfaceStore.setOpen(slotId, true)
    }

    override fun onStop() {
        super.onStop()
        mouseSurfaceStore.setOpen(slotId, false)
        releaseMoonlightButtons()
    }

    // Leaving the surface must never strand a held button on the host.
    private fun releaseMoonlightButtons() {
        val conn = moonlight.get(connectionId) ?: return
        if (mlLeftSent) {
            conn.sendMouseButton(false, MoonlightControlProtocol.MOUSE_BUTTON_LEFT)
            mlLeftSent = false
        }
        if (mlRightSent) {
            conn.sendMouseButton(false, MoonlightControlProtocol.MOUSE_BUTTON_RIGHT)
            mlRightSent = false
        }
        if (mlMiddleSent) {
            conn.sendMouseButton(false, MoonlightControlProtocol.MOUSE_BUTTON_MIDDLE)
            mlMiddleSent = false
        }
    }

    private fun latestFingers(): TouchpadSurfaceView.TouchpadState {
        val frame = lastReported?.fingers?.copy() ?: TouchpadSurfaceView.TouchpadState()
        frame.eventTimeMs = SystemClock.uptimeMillis()
        return frame
    }

    // A middle tap replays as a short press-and-release so the edge survives frame pacing.
    private fun pulseMiddleClick() {
        middleHeld = true
        report(latestFingers())
        views.root.postDelayed({
            middleHeld = false
            report(latestFingers())
        }, MIDDLE_CLICK_PULSE_MS)
    }

    private fun report(
        fingers: TouchpadSurfaceView.TouchpadState,
        scrollNotches: Int = 0,
    ) {
        inputRateStore.recordScreenSample()
        lastReported = MouseWireState(fingers, leftHeld, rightHeld, middleHeld)
        val summary = hub.summary(connectionId) ?: return
        if (!summary.live.isLiveLink()) return
        when (summary.kind) {
            ConnectionKind.SATELLITE ->
                sendMouseReport(fingers, leftHeld, rightHeld, middleHeld, scrollNotches)
            ConnectionKind.MOONLIGHT ->
                sendMoonlightMouse(fingers, scrollNotches)
            else -> Unit
        }
    }

    // Satellite only: the UDP frames are lossy state, so the pacer re-asserts them.
    // Moonlight mouse packets are edge events on ENet's reliable channel; replaying
    // them would replay clicks.
    override fun resendOneIfReady() {
        val state = lastReported ?: return
        val summary = hub.summary(connectionId) ?: return
        if (summary.kind != ConnectionKind.SATELLITE) return
        if (!summary.live.isLiveLink()) return
        // The fingers object mutates on the UI thread: copy() is the stable
        // comparison base (a torn read just costs one extra burst). Scroll is an
        // event, never state, so a resend always carries zero scroll.
        val snapshot = state.copy(fingers = state.fingers.copy())
        val changed = snapshot != lastResentSnapshot
        if (changed) lastResentSnapshot = snapshot
        if (!resendDue(changed)) return
        sendMouseReport(snapshot.fingers, snapshot.leftHeld, snapshot.rightHeld, snapshot.middleHeld, scrollNotches = 0)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_touchpad_overlay, menu)
        optionsMenu = menu
        paintConnectionMenuItem(menu.findItem(R.id.action_connection_info), currentSummary)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_connection_info -> {
                showConnectionDialog(currentSummary)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onConnectionSummaryChanged(summary: ConnectionSummary?) {
        currentSummary = summary
        paintConnectionMenuItem(optionsMenu?.findItem(R.id.action_connection_info), summary)
    }

    private fun sendMoonlightMouse(
        fingers: TouchpadSurfaceView.TouchpadState,
        scrollNotches: Int,
    ) {
        val conn = moonlight.get(connectionId) ?: return
        if (leftHeld != mlLeftSent) {
            conn.sendMouseButton(leftHeld, MoonlightControlProtocol.MOUSE_BUTTON_LEFT)
            mlLeftSent = leftHeld
        }
        if (rightHeld != mlRightSent) {
            conn.sendMouseButton(rightHeld, MoonlightControlProtocol.MOUSE_BUTTON_RIGHT)
            mlRightSent = rightHeld
        }
        if (middleHeld != mlMiddleSent) {
            conn.sendMouseButton(middleHeld, MoonlightControlProtocol.MOUSE_BUTTON_MIDDLE)
            mlMiddleSent = middleHeld
        }
        if (scrollNotches != 0) {
            conn.sendMouseScroll(
                (scrollNotches * WHEEL_DELTA_PER_NOTCH)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
            )
        }
        if (!fingers.finger0Active) {
            mlTrackingId = Int.MIN_VALUE
            return
        }
        val x = fingers.finger0X.toInt()
        val y = fingers.finger0Y.toInt()
        if (fingers.finger0TrackingId != mlTrackingId) {
            // A fresh touch anchors here instead of jumping the cursor across the pad.
            mlTrackingId = fingers.finger0TrackingId
            mlLastX = x
            mlLastY = y
            return
        }
        mlRemX += (x - mlLastX) * MOONLIGHT_MOVE_SCALE
        mlRemY += (y - mlLastY) * MOONLIGHT_MOVE_SCALE
        mlLastX = x
        mlLastY = y
        val dx = mlRemX.toInt()
        val dy = mlRemY.toInt()
        if (dx == 0 && dy == 0) return
        mlRemX -= dx
        mlRemY -= dy
        conn.sendMouseMoveRel(dx, dy)
    }

    @Suppress("LongParameterList")
    private fun sendMouseReport(
        fingers: TouchpadSurfaceView.TouchpadState,
        left: Boolean,
        right: Boolean,
        middle: Boolean,
        scrollNotches: Int,
    ) {
        val scroll =
            (scrollNotches * WHEEL_DELTA_PER_NOTCH)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        satellite.get(connectionId)?.sendTouchpad(
            slotId,
            fingers.finger0Active,
            fingers.finger1Active,
            buttonPressed = left,
            rightPressed = right,
            middlePressed = middle,
            fingers.finger0TrackingId,
            fingers.finger0X,
            fingers.finger0Y,
            fingers.finger1TrackingId,
            fingers.finger1X,
            fingers.finger1Y,
            fingers.eventTimeMs,
            scrollDelta = scroll,
        )
    }

    companion object {
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID

        private const val MIDDLE_CLICK_PULSE_MS = 70L
        private const val WHEEL_DELTA_PER_NOTCH = 120

        // One full sweep of the move surface (65535 normalized units) travels this many
        // host pixels; the float remainders keep slow drags from rounding to nothing.
        private const val MOONLIGHT_MOVE_PX_PER_SWEEP = 1800f
        private const val MOONLIGHT_MOVE_SCALE = MOONLIGHT_MOVE_PX_PER_SWEEP / 65535f
    }
}
