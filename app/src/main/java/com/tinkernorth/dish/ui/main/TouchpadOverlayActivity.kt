// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.databinding.ActivityTouchpadOverlayBinding
import com.tinkernorth.dish.ui.common.TouchpadReportBuffer
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import com.tinkernorth.dish.ui.common.paintConnectionMenuItem
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.showConnectionDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class TouchpadOverlayActivity : BaseInputOverlayActivity() {
    private lateinit var binding: ActivityTouchpadOverlayBinding

    // @Volatile for main-thread write / resend-thread read.
    @Volatile private var lastReportedState: TouchpadSurfaceView.TouchpadState? = null

    // The wire frame, one per sending thread.
    private val uiReport = TouchpadReportBuffer()
    private val resendReport = TouchpadReportBuffer()

    private var slotId: String = VIRTUAL_SLOT_ID
    private var clickHeld = false

    private var optionsMenu: Menu? = null
    private var currentSummary: ConnectionSummary? = null

    override fun rootView(): View = binding.root

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
    private var lastResentSnapshot: TouchpadSurfaceView.TouchpadState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: VIRTUAL_SLOT_ID
        installBaseScaffolding()

        setupDishToolbar(binding.overlayToolbar)
        binding.overlayToolbar.setTitle(R.string.overlay_title_touchpad)
        installRateReadout(
            slotId = slotId,
            motionOn = null,
        ) { binding.overlayToolbar.subtitle = it }

        binding.btnPadClick.onHeldChanged = ::onPadClickHeld
        binding.touchpadMovePad.clickWhenTouched = false
        binding.touchpadMovePad.label = getString(R.string.touchpad_pad_move_label)
        binding.touchpadMovePad.hint = getString(R.string.touchpad_pad_move_hint)
        binding.touchpadMovePad.listener = MovePadListener()
    }

    private inner class MovePadListener : TouchpadSurfaceView.Listener {
        override fun onTouchpadStateChanged(state: TouchpadSurfaceView.TouchpadState) {
            state.buttonPressed = clickHeld
            report(state)
        }
    }

    private fun onPadClickHeld(held: Boolean) {
        clickHeld = held
        report(latestFrame())
    }

    // The click button and the move surface merge into the slot's single frame stream:
    // fingers come from the surface, the pad click from the button, so a click with no
    // finger down is still a valid frame.
    private fun latestFrame(): TouchpadSurfaceView.TouchpadState {
        val frame = lastReportedState?.copy() ?: TouchpadSurfaceView.TouchpadState()
        frame.buttonPressed = clickHeld
        frame.eventTimeMs = SystemClock.uptimeMillis()
        return frame
    }

    private fun report(state: TouchpadSurfaceView.TouchpadState) {
        inputRateStore.recordScreenSample()
        lastReportedState = state
        when (pointerRouteFor(hub.summary(connectionId))) {
            PointerRoute.SATELLITE -> sendSatelliteTouchpadReport(state, uiReport)
            PointerRoute.MOONLIGHT -> moonlight.get(connectionId)?.let { sendTouchpadReport(it, state, uiReport) }
            PointerRoute.NONE -> Unit
        }
    }

    override fun resendOneIfReady() {
        val state = lastReportedState ?: return
        if (!pointerResendAllowed(hub.summary(connectionId))) return
        // The live state object mutates on the UI thread: copy() is the
        // stable comparison base (a torn read just costs one extra burst).
        val changed = state != lastResentSnapshot
        if (changed) lastResentSnapshot = state.copy()
        if (!resendDue(changed)) return
        sendSatelliteTouchpadReport(state, resendReport)
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

    // Resolves connection by id each call so reconnects after alive-poll death pick up new session handle.
    private fun sendSatelliteTouchpadReport(
        state: TouchpadSurfaceView.TouchpadState,
        buffer: TouchpadReportBuffer,
    ) {
        satellite.get(connectionId)?.let { sendTouchpadReport(it, state, buffer) }
    }

    private fun sendTouchpadReport(
        sink: com.tinkernorth.dish.source.connection.TelemetrySink,
        state: TouchpadSurfaceView.TouchpadState,
        buffer: TouchpadReportBuffer,
    ) {
        sink.sendTouchpad(slotId, buffer.reportOf(state, buttonPressed = state.buttonPressed))
    }

    companion object {
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID
    }
}
