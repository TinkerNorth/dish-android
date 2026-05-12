// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Intent
import android.os.Bundle
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
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.util.GamepadActivityHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity :
    GameActivity(),
    SlotActionListener {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var controllerAdapter: ControllerAdapter

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    private lateinit var gamepadHost: GamepadActivityHost

    // ═══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    //
    //  Wake-lock state, the dim-after-idle overlay, and physical-gamepad
    //  pass-through are owned by GamepadActivityHost so the wiring matches
    //  every other Dish activity. Anything specific to the dashboard
    //  (viewmodel observation, controller adapter) lives here.
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install() }
        controllerAdapter = ControllerAdapter(this)
        setupUI()
        observeViewModel()
        // Auto-reconnect is driven exclusively by ConnectionForegroundObserver
        // (ProcessLifecycleOwner) so the cold-start and return-to-foreground
        // paths share one entry point.
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
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
}
