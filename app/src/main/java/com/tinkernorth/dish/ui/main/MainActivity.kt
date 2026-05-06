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
import androidx.annotation.ColorRes
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
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.xusbToHid
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.util.LowPowerManager
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
    private lateinit var lowPowerBinding: OverlayLowPowerBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var controllerAdapter: ControllerAdapter
    private lateinit var inputManager: InputManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var wifi: WifiConnectionManager

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var inputProcessor: GamepadInputProcessor
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var lowPowerManager: LowPowerManager

    private val hasLivePhysical: Boolean
        get() =
            viewModel.uiState.value.physicalSlots
                .any { it.boundStatus?.live == ConnectionLive.CONNECTED }

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
        inputProcessor.reportSender = GamepadInputProcessor.ReportSender(::sendReport)
        inputManager.registerInputDeviceListener(this, null)
        syncControllers()
    }

    override fun onStop() {
        super.onStop()
        // Mirrors onStart: every listener registered there is torn down here.
        // The reportSender method reference captures `this`; nulling it on
        // stop releases the singleton's hold so a backgrounded Activity can
        // be GC'd. The next onStart reinstalls a fresh sender.
        inputProcessor.reportSender = null
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
        binding.dotServer.initDot(R.color.colorMuted)
        binding.btnManageConnections.setOnClickListener {
            startActivity(Intent(this, ConnectionsActivity::class.java))
        }
    }

    private fun sendReport(
        deviceId: Int,
        wButtons: Int,
        bLT: Int,
        bRT: Int,
        sLX: Int,
        sLY: Int,
        sRX: Int,
        sRY: Int,
    ) {
        // Exactly one physical slot (at most) owns this device id, so skip the
        // loop and look it up directly — without this a second controller's
        // reports would be broadcast to every physical slot's connection.
        val slot =
            viewModel.uiState.value.physicalSlots
                .firstOrNull { it.physicalDeviceId == deviceId } ?: return
        val cid = slot.boundConnectionId ?: return
        val summary = slot.boundStatus ?: return
        if (summary.live != ConnectionLive.CONNECTED) return
        when (summary.kind) {
            ConnectionKind.WIFI ->
                wifi.get(cid)?.sendReport(wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
            ConnectionKind.BLUETOOTH -> {
                // Physical controllers emit an XUSB-layout wButtons; the BT HID
                // descriptor expects a different bit layout plus a hat-switch
                // d-pad. See xusbToHid.
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
                    ) ?: return
                btRegistry.sendReport(cid, report)
            }
        }
    }

    private fun setupPower() {
        wakeLockManager = WakeLockManager(this, window)
        wakeLockManager.views =
            WakeLockManager.Views(
                tvScreenLock = binding.tvScreenLock,
                tvWakeLock = binding.tvWakeLock,
            )
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
            viewModel.uiState.value.connections
                .count { it.live == ConnectionLive.CONNECTED }
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
    }

    private fun updateUI(s: MainUiState) {
        val liveCount = s.connections.count { it.live == ConnectionLive.CONNECTED }
        val totalCount = s.connections.size
        binding.dotServer.setDotColor(if (liveCount > 0) R.color.colorSuccess else R.color.colorMuted)
        binding.tvServerStatus.text =
            when {
                liveCount > 1 -> getString(R.string.status_active_connections, liveCount)
                liveCount == 1 -> s.connections.first { it.live == ConnectionLive.CONNECTED }.label
                totalCount > 0 -> getString(R.string.status_remembered, totalCount)
                else -> getString(R.string.status_no_connections)
            }
        binding.tvServerStatus.setTextColor(
            getColor(if (liveCount > 0) R.color.colorSuccess else R.color.colorMuted),
        )
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

    override fun onOpenGamepad() {
        val v = viewModel.uiState.value.virtualSlot
        val cid =
            v.boundConnectionId ?: run {
                Toast.makeText(this, getString(R.string.toast_bind_first), Toast.LENGTH_SHORT).show()
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
        if (hasLivePhysical) inputProcessor.handleKeyEvent(event)?.let { return it }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (hasLivePhysical) inputProcessor.handleMotionEvent(event)?.let { return it }
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
        // Prune slots for devices that went away while we were stopped — the
        // InputManager listener is unregistered during onStop, so without
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

private fun View.initDot(
    @ColorRes colorRes: Int,
) {
    background =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(colorRes))
        }
}

private fun View.setDotColor(
    @ColorRes colorRes: Int,
) {
    (background as? GradientDrawable)?.setColor(context.getColor(colorRes))
}
