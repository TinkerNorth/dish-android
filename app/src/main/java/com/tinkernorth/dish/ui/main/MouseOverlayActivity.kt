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
        views = inflateFor(hostFeaturesStore.featuresFor(hostId)?.extendedMouse == true)
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
        if (summary.kind != ConnectionKind.SATELLITE) return
        sendMouseReport(fingers, leftHeld, rightHeld, middleHeld, scrollNotches)
    }

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
    }
}
