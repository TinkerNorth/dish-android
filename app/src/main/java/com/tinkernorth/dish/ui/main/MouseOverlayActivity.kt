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
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.ui.common.HoldButtonView
import com.tinkernorth.dish.ui.common.ScrollStripView
import com.tinkernorth.dish.ui.common.TouchpadReportBuffer
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

    // Main-thread write per frame / resend-thread read per tick, with nothing allocated on either.
    private val reportLatch = MouseReportLatch()

    // The satellite's wire frame, one per sending thread.
    private val uiReport = TouchpadReportBuffer()
    private val resendReport = TouchpadReportBuffer()

    private var slotId: String = VIRTUAL_SLOT_ID
    private var leftHeld = false
    private var rightHeld = false
    private var middleHeld = false

    // UI-thread only: what the Moonlight host was last told, and the finger anchor the
    // relative moves accumulate against.
    private val mouseMover = MoonlightMouseMover()
    private var moonlightMouseSink: MoonlightConnectionMouseSink? = null

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

        wireMouseButtons()
        wireMovePad()
    }

    private inner class MovePadListener : TouchpadSurfaceView.Listener {
        override fun onTouchpadStateChanged(state: TouchpadSurfaceView.TouchpadState) {
            report(state)
        }
    }

    private fun setLeftHeld(held: Boolean) {
        leftHeld = held
        report(latestFingers())
    }

    private fun setRightHeld(held: Boolean) {
        rightHeld = held
        report(latestFingers())
    }

    private fun scrollBy(notches: Int) {
        report(latestFingers(), scrollNotches = notches)
    }

    private fun wireMouseButtons() {
        views.left.onHeldChanged = ::setLeftHeld
        views.right?.onHeldChanged = ::setRightHeld
        views.strip?.onScroll = ::scrollBy
        views.strip?.onMiddleTap = ::pulseMiddleClick
    }

    private fun wireMovePad() {
        views.movePad.clickWhenTouched = false
        views.movePad.label = getString(R.string.touchpad_pad_move_label)
        views.movePad.hint = getString(R.string.touchpad_pad_move_hint)
        views.movePad.listener = MovePadListener()
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

    private fun releaseMoonlightButtons() {
        val conn = moonlight.get(connectionId) ?: return
        mouseMover.releaseButtons(moonlightMouseSinkOn(conn))
    }

    private fun latestFingers(): TouchpadSurfaceView.TouchpadState = reportLatch.latestFingersAt(SystemClock.uptimeMillis())

    // A middle tap replays as a short press-and-release so the edge survives frame pacing.
    private fun pulseMiddleClick() {
        middleHeld = true
        report(latestFingers())
        views.root.postDelayed(::releaseMiddleClick, MIDDLE_CLICK_PULSE_MS)
    }

    private fun releaseMiddleClick() {
        middleHeld = false
        report(latestFingers())
    }

    private fun report(
        fingers: TouchpadSurfaceView.TouchpadState,
        scrollNotches: Int = 0,
    ) {
        inputRateStore.recordScreenSample()
        reportLatch.record(fingers, leftHeld, rightHeld, middleHeld)
        when (pointerRouteFor(hub.summary(connectionId))) {
            PointerRoute.SATELLITE -> sendMouseReport(uiReport, fingers, leftHeld, rightHeld, middleHeld, scrollNotches)
            PointerRoute.MOONLIGHT -> sendMoonlightMouse(fingers, scrollNotches)
            PointerRoute.NONE -> Unit
        }
    }

    // Scroll is an event, never state, so a resend always carries zero scroll.
    override fun resendOneIfReady() {
        if (!reportLatch.hasReported) return
        if (!pointerResendAllowed(hub.summary(connectionId))) return
        val changed = reportLatch.refreshResendSnapshot()
        if (!resendDue(changed)) return
        sendMouseReport(
            resendReport,
            reportLatch.resentFingers,
            reportLatch.resentLeftHeld,
            reportLatch.resentRightHeld,
            reportLatch.resentMiddleHeld,
            scrollNotches = 0,
        )
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

    // The mover only learns of a frame the host will hear about: with no connection the
    // held buttons stay unsent, so the next live frame carries their edges.
    private fun sendMoonlightMouse(
        fingers: TouchpadSurfaceView.TouchpadState,
        scrollNotches: Int,
    ) {
        val conn = moonlight.get(connectionId) ?: return
        mouseMover.onFrame(moonlightMouseSinkOn(conn), fingers, scrollNotches, leftHeld, rightHeld, middleHeld)
    }

    private fun moonlightMouseSinkOn(conn: MoonlightConnection): MoonlightConnectionMouseSink {
        val sink = moonlightMouseSinkFor(moonlightMouseSink, conn)
        moonlightMouseSink = sink
        return sink
    }

    private fun sendMouseReport(
        buffer: TouchpadReportBuffer,
        fingers: TouchpadSurfaceView.TouchpadState,
        left: Boolean,
        right: Boolean,
        middle: Boolean,
        scrollNotches: Int,
    ) {
        val scroll = wheelDeltaFor(scrollNotches).toShort()
        satellite.get(connectionId)?.sendTouchpad(
            slotId,
            buffer.reportOf(fingers, buttonPressed = left, rightPressed = right, middlePressed = middle, scrollDelta = scroll),
        )
    }

    companion object {
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID

        private const val MIDDLE_CLICK_PULSE_MS = 70L
    }
}
