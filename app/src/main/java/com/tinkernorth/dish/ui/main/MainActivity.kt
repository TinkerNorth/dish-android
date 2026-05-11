// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.LowPowerTouchGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity :
    GameActivity(),
    SlotActionListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var lowPowerBinding: OverlayLowPowerBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var controllerAdapter: ControllerAdapter

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var wakeState: WakeStateController
    private lateinit var lowPowerManager: LowPowerManager
    private val lowPowerTouchGate = LowPowerTouchGate()

    // ═══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    //
    //  onCreate / [implicit destroy]   construction + view tree
    //  onStart  / onStop               every active behavior pairs here
    //
    //  Anything that posts to a Handler, holds a wake lock, mutates a
    //  singleton, or registers a system listener belongs in onStart/onStop —
    //  not onCreate/onDestroy — so it stops while the user can't see us.
    //
    //  Physical-gamepad tracking, native-slot bindings, and the partial wake
    //  lock are all owned by process-scoped singletons in DishApplication so
    //  they survive the MainActivity → GamepadOverlayActivity hand-off. The
    //  per-window `FLAG_KEEP_SCREEN_ON` is still toggled here because the
    //  flag is window-scoped, but it's driven off the same shared signal.
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Low-power overlay is a <merge> include in activity_main.xml, so its
        // IDs aren't surfaced on ActivityMainBinding — bind() pulls them off
        // the already-inflated tree.
        lowPowerBinding = OverlayLowPowerBinding.bind(binding.root)
        controllerAdapter = ControllerAdapter(this)
        setupUI()
        setupPower()
        observeViewModel()
        // Auto-reconnect is driven exclusively by ConnectionForegroundObserver
        // (ProcessLifecycleOwner) so the cold-start and return-to-foreground
        // paths share one entry point.
    }

    override fun onStop() {
        super.onStop()
        // The window flag is harmless while we're hidden, but the dim-screen
        // state machine has its own timers — kill them so the next onStart
        // re-derives the state cleanly.
        lowPowerManager.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UI
    // ═══════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        binding.rvControllers.adapter = controllerAdapter
        binding.btnManageConnections.setOnClickListener {
            startActivity(Intent(this, ConnectionsActivity::class.java))
        }
    }

    private fun setupPower() {
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views =
            LowPowerManager.Views(
                llCountdownBanner = lowPowerBinding.llCountdownBanner,
                tvCountdownSeconds = lowPowerBinding.tvCountdownSeconds,
                flLowPowerOverlay = lowPowerBinding.flLowPowerOverlay,
                tvLowPowerTime = lowPowerBinding.tvLowPowerTime,
                tvLowPowerStatus = lowPowerBinding.tvLowPowerStatus,
            )
        lowPowerManager.activeControllerCount = { wakeState.streamingSlotCount.value }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect { updateUI(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.events.collect { handleEvent(it) } }
        }
        // shouldKeepScreenOn is process-scoped; we just mirror it onto this
        // window's FLAG_KEEP_SCREEN_ON and forward it to the local dim-screen
        // state machine. On every STARTED entry the collector replays the
        // current value, so re-resuming after the gamepad overlay closes
        // restores the right state immediately.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeState.shouldKeepScreenOn.collect(::applyScreenOn)
            }
        }
    }

    private fun applyScreenOn(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lowPowerManager.onLockStateChanged(active)
    }

    private fun updateUI(s: MainUiState) {
        val liveCount = s.connections.count { it.live == ConnectionLive.CONNECTED }
        val totalCount = s.connections.size
        binding.tvConnectionsSummary.text =
            when {
                liveCount == 0 && totalCount == 0 -> getString(R.string.status_tap_manage)
                liveCount == 0 -> getString(R.string.status_remembered, totalCount)
                else -> getString(R.string.status_connected_of, liveCount, totalCount)
            }
        controllerAdapter.submitSlots(s.slots, s.connections)
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowToast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            is MainEvent.ShowPairingDialog ->
                Toast
                    .makeText(this, getString(R.string.toast_pairing_needed), Toast.LENGTH_LONG)
                    .show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SLOT ACTIONS
    // ═══════════════════════════════════════════════════════════════════════

    override fun onSlotTapped(slotId: String) = controllerAdapter.toggleExpanded(slotId)

    override fun onBind(
        slotId: String,
        connectionId: String,
    ) = viewModel.bindSlot(slotId, connectionId)

    override fun onUnbind(slotId: String) = viewModel.unbindSlot(slotId)

    override fun onChangeDeviceType(
        slotId: String,
        connectionId: String,
        type: Int,
    ) = viewModel.setSatelliteControllerType(connectionId, slotId, type)

    override fun onOpenGamepad() {
        val v = viewModel.uiState.value.virtualSlot
        val cid =
            v.boundConnectionId ?: run {
                Toast.makeText(this, getString(R.string.toast_bind_first), Toast.LENGTH_SHORT).show()
                return
            }
        val summary = v.boundStatus
        // BT carries its profile in summary.btProfile; satellites carry a per-slot
        // controller type the user can flip from the dashboard. Either signals
        // "use PS layout" to the overlay.
        val usePs =
            summary?.btProfile == "PlayStation" ||
                summary?.satelliteControllerTypes?.get(VIRTUAL_SLOT_ID) ==
                com.tinkernorth.dish.data.network.CONTROLLER_TYPE_PLAYSTATION
        val intent =
            Intent(this, GamepadOverlayActivity::class.java).apply {
                putExtra(GamepadOverlayActivity.EXTRA_CONNECTION_ID, cid)
                putExtra(GamepadOverlayActivity.EXTRA_USE_PS_LAYOUT, usePs)
            }
        startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DISPATCH
    //
    //  Physical gamepad key + motion events are intercepted at the Activity
    //  dispatch level and forwarded to the native pipeline via JNI. Doing it
    //  here (instead of relying on the GameActivity filter callbacks alone)
    //  prevents Android's input system from synthesizing fallback DPAD key
    //  presses for stick movements whose joystick MOVE events otherwise
    //  reach no consuming view. The GameActivity filters in
    //  satellite_jni.cpp remain wired as a safety net for events that
    //  somehow bypass Activity dispatch.
    //
    //  Touch events fall through to the View hierarchy normally — only the
    //  low-power gate runs here.
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isGamepadSource(event.source) &&
            SatelliteNative.processGamepadKeyEvent(
                event.deviceId,
                event.source,
                event.action,
                event.keyCode,
            )
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            SatelliteNative.processGamepadMotionEvent(
                event.deviceId,
                event.source,
                event.action,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                event.getAxisValue(MotionEvent.AXIS_RX),
                event.getAxisValue(MotionEvent.AXIS_RY),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            )
        ) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Order matters: read overlayActive *before* notifying the manager so a
        // DOWN that dismisses the overlay still wins the gate (and consumes the
        // rest of the gesture so the underlying button doesn't get a click).
        val overlayActive = lowPowerManager.state == LowPowerManager.State.ACTIVE
        val consume = lowPowerTouchGate.onDispatch(ev.action, overlayActive)
        if (ev.action == MotionEvent.ACTION_DOWN && wakeState.shouldKeepScreenOn.value) {
            lowPowerManager.onUserInteraction()
        }
        return if (consume) true else super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Emit a release-all report per bound device so no button stays held
        // server-side across focus loss.
        if (!hasFocus) SatelliteNative.releaseAllPhysicalReports()
    }
}
