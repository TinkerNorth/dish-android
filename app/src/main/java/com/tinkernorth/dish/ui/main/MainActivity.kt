// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.LowPowerTouchGate
import com.tinkernorth.dish.util.WakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity :
    GameActivity(),
    InputManager.InputDeviceListener,
    SlotActionListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var lowPowerBinding: OverlayLowPowerBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var controllerAdapter: ControllerAdapter
    private lateinit var inputManager: InputManager

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var hub: ConnectionHub
    private lateinit var wakeLockManager: WakeLockManager
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
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Low-power overlay is a <merge> include in activity_main.xml, so its
        // IDs aren't surfaced on ActivityMainBinding — bind() pulls them off
        // the already-inflated tree.
        lowPowerBinding = OverlayLowPowerBinding.bind(binding.root)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        controllerAdapter = ControllerAdapter(this)
        setupUI()
        setupPower()
        observeViewModel()
        // Auto-reconnect is driven exclusively by ConnectionForegroundObserver
        // (ProcessLifecycleOwner) so the cold-start and return-to-foreground
        // paths share one entry point.
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, null)
        syncControllers()
    }

    override fun onStop() {
        super.onStop()
        // Mirrors onStart: every listener registered there is torn down here.
        // Drop every native slot binding; the next onStart will re-derive them
        // from the bindings observer (uiState × satellite slot states).
        SatelliteNative.clearAllPhysicalSlots()
        inputManager.unregisterInputDeviceListener(this)
        wakeLockManager.release()
        lowPowerManager.cancel()
        // wake/low-power state re-engages on the next onStart via the
        // StateFlow collector's repeatOnLifecycle(STARTED) replay.
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
        wakeLockManager = WakeLockManager(this, window)
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views =
            LowPowerManager.Views(
                llCountdownBanner = lowPowerBinding.llCountdownBanner,
                tvCountdownSeconds = lowPowerBinding.tvCountdownSeconds,
                flLowPowerOverlay = lowPowerBinding.flLowPowerOverlay,
                tvLowPowerTime = lowPowerBinding.tvLowPowerTime,
                tvLowPowerStatus = lowPowerBinding.tvLowPowerStatus,
            )
        lowPowerManager.activeControllerCount = {
            viewModel.uiState.value.streamingSlotCount
        }
        wakeLockManager.onLockStateChanged = { lowPowerManager.onLockStateChanged(it) }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect { updateUI(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.events.collect { handleEvent(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { observeNativeBindings() }
        }
    }

    /**
     * Push physical-slot bindings into the native input pipeline whenever the
     * derived state changes. Sources:
     *   - [MainViewModel.uiState] for slot ↔ connection bindings and live state
     *   - each [com.tinkernorth.dish.data.network.SatelliteConnection.slots]
     *     flow for the per-slot `registered` flag and `controllerIndex`
     *
     * When a satellite slot isn't registered yet (the brief window between
     * `markConnected` and the addController ACK), we leave the binding cleared
     * so reports don't leak to the server as "unknown controller".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeNativeBindings() {
        satellite.connections
            .flatMapLatest { conns ->
                val slotFlows = conns.values.map { it.slots }
                val triggers =
                    if (slotFlows.isEmpty()) {
                        flowOf(Unit)
                    } else {
                        combine(slotFlows) { Unit }
                    }
                combine(viewModel.uiState, triggers) { ui, _ -> ui }
            }.collect { ui -> pushPhysicalSlotBindings(ui) }
    }

    private fun pushPhysicalSlotBindings(ui: MainUiState) {
        for (slot in ui.physicalSlots) {
            val deviceId = slot.physicalDeviceId
            if (deviceId < 0) continue
            val cid = slot.boundConnectionId
            val summary = slot.boundStatus
            if (cid == null || summary == null || summary.live != ConnectionLive.CONNECTED) {
                SatelliteNative.unbindPhysicalSlot(deviceId)
                continue
            }
            when (summary.kind) {
                ConnectionKind.SATELLITE -> {
                    val sat = satellite.get(cid)
                    val info = sat?.slots?.value?.get(slot.id)
                    if (sat == null || sat.handle < 0 || info == null || !info.registered) {
                        SatelliteNative.unbindPhysicalSlot(deviceId)
                    } else {
                        SatelliteNative.bindPhysicalSlotSatellite(deviceId, sat.handle, info.controllerIndex)
                    }
                }
                ConnectionKind.BLUETOOTH ->
                    SatelliteNative.bindPhysicalSlotBluetooth(deviceId, cid)
            }
        }
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
        wakeLockManager.update(liveCount > 0)
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
                event.deviceId, event.source, event.action, event.keyCode,
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
        if (ev.action == MotionEvent.ACTION_DOWN && wakeLockManager.isActive) {
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

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DEVICE LISTENER
    // ═══════════════════════════════════════════════════════════════════════

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if (!isGamepad(dev)) return
        pushDeadzones(dev)
        viewModel.onControllerConnected(deviceId, dev.name)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        SatelliteNative.unbindPhysicalSlot(deviceId)
        viewModel.onControllerDisconnected(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId)
        if (dev == null || !isGamepad(dev)) {
            SatelliteNative.unbindPhysicalSlot(deviceId)
            viewModel.onControllerDisconnected(deviceId)
        }
    }

    private fun syncControllers() {
        val liveIds = mutableSetOf<Int>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (!isGamepad(dev)) continue
            liveIds += id
            pushDeadzones(dev)
            viewModel.onControllerConnected(id, dev.name)
        }
        // Prune slots for devices that went away while we were stopped — the
        // InputManager listener is unregistered during onStop, so without
        // this any controller unplugged in the background would stay in the
        // UI forever.
        for (slot in viewModel.uiState.value.physicalSlots) {
            if (slot.physicalDeviceId !in liveIds) {
                SatelliteNative.unbindPhysicalSlot(slot.physicalDeviceId)
                viewModel.onControllerDisconnected(slot.physicalDeviceId)
            }
        }
    }

    /**
     * Query the device's per-axis flat (deadzone) values once and push them
     * into the native processor, which uses them in its inline deadzone gate.
     * Pushed at device-add time so the native input thread never has to cross
     * back into Java per event to look them up.
     */
    private fun pushDeadzones(dev: InputDevice) {
        val src = InputDevice.SOURCE_JOYSTICK
        SatelliteNative.setDeviceDeadzones(
            dev.id,
            dev.getMotionRange(MotionEvent.AXIS_X, src)?.flat ?: 0f,
            dev.getMotionRange(MotionEvent.AXIS_Y, src)?.flat ?: 0f,
            dev.getMotionRange(MotionEvent.AXIS_Z, src)?.flat ?: 0f,
            dev.getMotionRange(MotionEvent.AXIS_RZ, src)?.flat ?: 0f,
        )
        logDeviceCapabilities(dev)
    }

    // One-line per-device dump of every motion range the controller declares,
    // so the native filter's axis choices can be cross-checked against what
    // the device actually advertises (Z/RZ vs RX/RY for right stick, hat
    // axes vs DPAD keycodes, etc.).
    private fun logDeviceCapabilities(dev: InputDevice) {
        val axes = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        )
        val names = arrayOf(
            "X", "Y", "Z", "RZ", "RX", "RY", "HX", "HY", "LT", "RT", "BR", "GS",
        )
        val sb = StringBuilder()
        sb.append("DEVCAPS id=").append(dev.id)
            .append(" name=\"").append(dev.name).append('"')
            .append(" sources=0x").append(Integer.toHexString(dev.sources))
            .append(" ranges=[")
        var first = true
        for (i in axes.indices) {
            val r = dev.getMotionRange(axes[i], InputDevice.SOURCE_JOYSTICK) ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append(names[i])
                .append('(').append(r.min).append("..").append(r.max)
                .append(",flat=").append(r.flat).append(')')
        }
        sb.append(']')
        Log.i("SatelliteJNI", sb.toString())
    }

    private fun isGamepad(d: InputDevice): Boolean {
        if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        val s = d.sources
        val isGame =
            (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isGame) return false
        return d
            .hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT,
            ).any { it }
    }
}
