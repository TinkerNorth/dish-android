// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.databinding.ActivityTouchpadOverlayBinding
import com.tinkernorth.dish.ui.common.TouchpadPadCoordinator
import com.tinkernorth.dish.ui.common.TouchpadSurfaceView
import com.tinkernorth.dish.ui.common.paintConnectionMenuItem
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.showConnectionDialog
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TouchpadOverlayActivity : BaseInputOverlayActivity() {
    private lateinit var binding: ActivityTouchpadOverlayBinding

    // @Volatile for main-thread write / resend-thread read.
    @Volatile private var lastReportedState: TouchpadSurfaceView.TouchpadState? = null

    private var slotId: String = VIRTUAL_SLOT_ID

    private val padCoordinator = TouchpadPadCoordinator<TouchpadSurfaceView>()

    private var optionsMenu: Menu? = null
    private var currentSummary: ConnectionSummary? = null

    override fun rootView(): View = binding.root

    override val resendIntervalNs: Long = BaseInputOverlayActivity.RESEND_INTERVAL_NS_DEFAULT

    // Resend-thread-only (single-threaded Handler dispatcher).
    private var lastResentSnapshot: TouchpadSurfaceView.TouchpadState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installBaseScaffolding()
        slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: VIRTUAL_SLOT_ID

        setupDishToolbar(binding.overlayToolbar)
        binding.overlayToolbar.setTitle(R.string.overlay_title_touchpad)
        installRateReadout(
            slotId = slotId,
            motionOn = null,
        ) { binding.overlayToolbar.subtitle = it }

        bindPad(
            pad = binding.touchpadClickPad,
            other = binding.touchpadMovePad,
            clickWhenTouched = true,
            labelRes = R.string.touchpad_pad_click_label,
            hintRes = R.string.touchpad_pad_click_hint,
        )
        bindPad(
            pad = binding.touchpadMovePad,
            other = binding.touchpadClickPad,
            clickWhenTouched = false,
            labelRes = R.string.touchpad_pad_move_label,
            hintRes = R.string.touchpad_pad_move_hint,
        )
    }

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
                    if (!padCoordinator.mayWrite(pad)) return
                    inputRateStore.recordScreenSample()
                    lastReportedState = state
                    val summary = hub.summary(connectionId) ?: return
                    if (summary.live != LinkState.Connected) return
                    if (summary.kind != ConnectionKind.SATELLITE) return
                    sendSatelliteTouchpadReport(state)
                }

                override fun onTouchActivityChanged(active: Boolean) {
                    if (active) {
                        if (padCoordinator.onTouchStart(pad)) {
                            other.accepting = false
                        }
                    } else {
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
        // The live state object mutates on the UI thread: copy() is the
        // stable comparison base (a torn read just costs one extra burst).
        val changed = state != lastResentSnapshot
        if (changed) lastResentSnapshot = state.copy()
        if (!resendDue(changed)) return
        sendSatelliteTouchpadReport(state)
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
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_CONNECTION_ID = BaseInputOverlayActivity.EXTRA_CONNECTION_ID
    }
}
