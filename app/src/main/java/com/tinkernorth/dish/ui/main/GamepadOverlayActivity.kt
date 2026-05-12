// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.databinding.ActivityGamepadOverlayBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.hidToXusb
import com.tinkernorth.dish.ui.common.GamepadTouchView
import com.tinkernorth.dish.util.GamepadActivityHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen landscape activity hosting the on-screen touch gamepad.
 *
 * Bound to a single connection id passed via [EXTRA_CONNECTION_ID]; reports
 * are routed to that connection through either the [BluetoothGamepadRegistry]
 * or the matching [com.tinkernorth.dish.data.network.SatelliteConnection] in
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

    private lateinit var binding: ActivityGamepadOverlayBinding
    private lateinit var gamepadHost: GamepadActivityHost
    private var connectionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamepadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install() }
        hideSystemBars()

        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()
        binding.gamepadTouchView.listener = this
        binding.gamepadTouchView.usePlayStation = intent.getBooleanExtra(EXTRA_USE_PS_LAYOUT, false)
        binding.dotOverlay.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.colorMuted))
            }
        binding.btnExitGamepad.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect { refreshStatus() }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    private fun refreshStatus() {
        val summary = hub.summary(connectionId)
        val connected = summary?.live == ConnectionLive.CONNECTED
        binding.tvOverlayStatus.text =
            when {
                connected -> summary?.label ?: "Streaming"
                summary?.live == ConnectionLive.CONNECTING -> "Connecting…"
                summary == null -> "Unknown connection"
                else -> "Not connected"
            }
        (binding.dotOverlay.background as? GradientDrawable)?.setColor(
            getColor(if (connected) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        val summary = hub.summary(connectionId) ?: return
        if (summary.live != ConnectionLive.CONNECTED) return
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
            ConnectionKind.SATELLITE -> {
                // The touch view emits HID-layout button bits plus a
                // separate hat-switch; the satellite path wants an XUSB
                // wButtons with the d-pad folded back into the low nibble.
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
        }
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
    }
}
