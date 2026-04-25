// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.WifiConnectionManager
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.xusbToHid
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.TelemetryTracker
import com.tinkernorth.dish.util.WakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    InputManager.InputDeviceListener,
    SlotActionListener {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var controllerAdapter: ControllerAdapter
    private lateinit var inputManager: InputManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var wifi: WifiConnectionManager

    @Inject lateinit var hub: ConnectionHub

    private val inputProcessor = GamepadInputProcessor()
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var lowPowerManager: LowPowerManager
    private lateinit var telemetryTracker: TelemetryTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        controllerAdapter = ControllerAdapter(this)
        setupUI()
        setupManagers()
        observeViewModel()
        // Auto-reconnect is driven exclusively by ConnectionForegroundObserver
        // (ProcessLifecycleOwner) so the cold-start and return-to-foreground
        // paths share one entry point.
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UI
    // ═══════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        binding.rvControllers.adapter = controllerAdapter
        initDot(binding.dotServer)
        binding.btnManageConnections.setOnClickListener {
            startActivity(Intent(this, ConnectionsActivity::class.java))
        }
    }

    private fun initDot(v: View) {
        v.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.colorMuted))
            }
    }

    private fun setDot(
        v: View,
        colorRes: Int,
    ) {
        (v.background as? GradientDrawable)?.setColor(getColor(colorRes))
    }

    private fun setupManagers() {
        inputProcessor.reportSender =
            GamepadInputProcessor.ReportSender { deviceId, wButtons, bLT, bRT, sLX, sLY, sRX, sRY ->
                // Exactly one physical slot (at most) owns this device id,
                // so skip the loop and look it up directly — without this
                // a second controller's reports would be broadcast to every
                // physical slot's connection.
                val slot =
                    viewModel.uiState.value.physicalSlots
                        .firstOrNull { it.physicalDeviceId == deviceId } ?: return@ReportSender
                val cid = slot.boundConnectionId ?: return@ReportSender
                val summary = slot.boundStatus ?: return@ReportSender
                if (summary.live != ConnectionLive.CONNECTED) return@ReportSender
                when (summary.kind) {
                    ConnectionKind.WIFI ->
                        wifi.get(cid)?.sendReport(wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
                    ConnectionKind.BLUETOOTH -> {
                        // Physical controllers emit an XUSB-layout wButtons;
                        // the BT HID descriptor expects a different bit
                        // layout plus a hat-switch d-pad. See xusbToHid.
                        val (hidButtons, hat) = xusbToHid(wButtons)
                        val report =
                            btRegistry.buildReport(
                                cid,
                                hidButtons,
                                hat,
                                sLX.toShort(),
                                sLY.toShort(),
                                sRX.toShort(),
                                sRY.toShort(),
                                bLT,
                                bRT,
                            ) ?: return@ReportSender
                        btRegistry.sendReport(cid, report)
                    }
                }
            }

        wakeLockManager = WakeLockManager(this, window)
        wakeLockManager.views =
            WakeLockManager.Views(
                tvScreenLock = binding.tvScreenLock,
                tvWakeLock = binding.tvWakeLock,
            )
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views =
            LowPowerManager.Views(
                llCountdownBanner = findViewById(R.id.llCountdownBanner),
                tvCountdownSeconds = findViewById(R.id.tvCountdownSeconds),
                flLowPowerOverlay = findViewById(R.id.flLowPowerOverlay),
                tvLowPowerTime = findViewById(R.id.tvLowPowerTime),
                tvLowPowerStatus = findViewById(R.id.tvLowPowerStatus),
            )
        lowPowerManager.activeControllerCount = {
            viewModel.uiState.value.connections
                .count { it.live == ConnectionLive.CONNECTED }
        }
        wakeLockManager.onLockStateChanged = { lowPowerManager.onLockStateChanged(it) }
        telemetryTracker = TelemetryTracker(inputProcessor)
        telemetryTracker.start()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect { updateUI(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.events.collect { handleEvent(it) } }
        }
    }

    private fun updateUI(s: MainUiState) {
        val liveCount = s.connections.count { it.live == ConnectionLive.CONNECTED }
        setDot(binding.dotServer, if (liveCount > 0) R.color.colorSuccess else R.color.colorMuted)
        binding.tvServerStatus.text =
            when {
                liveCount > 1 -> "$liveCount active connections"
                liveCount == 1 -> s.connections.first { it.live == ConnectionLive.CONNECTED }.label
                s.connections.isNotEmpty() -> "${s.connections.size} remembered"
                else -> "No connections yet"
            }
        binding.tvServerStatus.setTextColor(
            getColor(if (liveCount > 0) R.color.colorSuccess else R.color.colorMuted),
        )
        binding.tvConnectionsSummary.text =
            when {
                liveCount == 0 && s.connections.isEmpty() -> "Tap Manage to add one"
                liveCount == 0 -> "${s.connections.size} remembered"
                else -> "$liveCount of ${s.connections.size} connected"
            }
        controllerAdapter.submitSlots(s.slots, s.connections)
        wakeLockManager.update(liveCount > 0)
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowToast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            is MainEvent.ShowPairingDialog ->
                Toast
                    .makeText(
                        this,
                        "Pairing needed — open Connections",
                        Toast.LENGTH_LONG,
                    ).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SLOT ACTIONS
    // ═══════════════════════════════════════════════════════════════════════

    override fun onSlotTapped(slotId: String) {
        controllerAdapter.toggleExpanded(slotId)
        controllerAdapter.submitSlots(viewModel.uiState.value.slots, viewModel.uiState.value.connections)
    }

    override fun onBind(
        slotId: String,
        connectionId: String,
    ) = viewModel.bindSlot(slotId, connectionId)

    override fun onUnbind(slotId: String) = viewModel.unbindSlot(slotId)

    override fun onOpenGamepad() {
        val v = viewModel.uiState.value.virtualSlot
        val cid =
            v.boundConnectionId ?: run {
                Toast.makeText(this, "Bind a connection first", Toast.LENGTH_SHORT).show()
                return
            }
        val summary = v.boundStatus
        val intent =
            Intent(this, GamepadOverlayActivity::class.java).apply {
                putExtra(GamepadOverlayActivity.EXTRA_CONNECTION_ID, cid)
                putExtra(GamepadOverlayActivity.EXTRA_USE_PS_LAYOUT, summary?.btProfile == "PlayStation")
            }
        startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DISPATCH (physical gamepads)
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.uiState.value.physicalSlots
                .any { it.boundStatus?.live == ConnectionLive.CONNECTED }
        ) {
            inputProcessor.handleKeyEvent(event)?.let { return it }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.uiState.value.physicalSlots
                .any { it.boundStatus?.live == ConnectionLive.CONNECTED }
        ) {
            inputProcessor.handleMotionEvent(event)?.let { return it }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && wakeLockManager.isActive) lowPowerManager.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Emit a release-all report per active device so no button stays held
        // server-side across focus loss.
        if (!hasFocus) inputProcessor.zeroAndSendAll()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DEVICE LISTENER
    // ═══════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, null)
        syncControllers()
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        lowPowerManager.cancel()
        telemetryTracker.stop()
        wakeLockManager.release()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if (isGamepad(dev)) viewModel.onControllerConnected(deviceId, dev.name)
    }

    override fun onInputDeviceRemoved(deviceId: Int) = viewModel.onControllerDisconnected(deviceId)

    override fun onInputDeviceChanged(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId)
        if (dev == null || !isGamepad(dev)) viewModel.onControllerDisconnected(deviceId)
    }

    private fun syncControllers() {
        val liveIds = mutableSetOf<Int>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (!isGamepad(dev)) continue
            liveIds += id
            viewModel.onControllerConnected(id, dev.name)
        }
        // Prune slots for devices that went away while we were paused — the
        // InputManager listener is unregistered during onPause, so without
        // this any controller unplugged in the background would stay in the
        // UI forever.
        for (slot in viewModel.uiState.value.physicalSlots) {
            if (slot.physicalDeviceId !in liveIds) {
                viewModel.onControllerDisconnected(slot.physicalDeviceId)
            }
        }
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
