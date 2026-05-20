// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.LinkState
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.ui.common.DishNotification
import com.tinkernorth.dish.ui.common.DishNotificationQueue
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

    @Inject lateinit var notifications: DishNotificationQueue

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
        // Native-library load failed in DishApplication.onCreate — route the
        // user to the themed fallback screen instead of crashing the moment
        // we touch any JNI surface (GameActivity itself loads native code).
        if (com.tinkernorth.dish.DishApplication.nativeLoadFailed) {
            startActivity(Intent(this, NativeUnavailableActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // `install(notifications)` wires the wake-state collectors AND the
        // themed notification host in one call — the manual findViewById +
        // bindLifecycle dance lived in three activities before; folding it
        // into the host eliminates the boilerplate.
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install(notifications) }
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
        val liveCount = s.connections.count { it.live == LinkState.Connected }
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
            // Server-supplied error strings from user-initiated paths —
            // the user just acted, so a warning that explains the result
            // is the right severity.
            is MainEvent.ShowToast ->
                notifications.warn(
                    title = event.message,
                    glyph = R.drawable.ic_satellite_off,
                )
            // Stale state on the row already says "Needs pairing"; the
            // banner adds an OPEN action so the user can jump straight to
            // the right satellite's PIN dialog. Same-key replacement so
            // repeat pair-required events for the same id don't stack.
            is MainEvent.ShowPairingDialog ->
                notifications.warn(
                    glyph = R.drawable.ic_satellite_off,
                    title = getString(R.string.notif_pairing_needed_title),
                    body = getString(R.string.notif_pairing_needed_body, event.connectionId),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_open),
                        ) { openConnectionsForPairing(event.connectionId) },
                    key = "pair-required:${event.connectionId}",
                )
        }
    }

    private fun openConnectionsForPairing(connectionId: String) {
        startActivity(
            Intent(this, ConnectionsActivity::class.java).apply {
                putExtra(ConnectionsActivity.EXTRA_PAIR_PROMPT_FOR_ID, connectionId)
            },
        )
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
                // No connection bound. The on-screen gamepad has nowhere to
                // route input to, so a transient INFO banner explaining the
                // next step is the right severity — auto-dismisses.
                notifications.info(
                    glyph = R.drawable.ic_gamepad,
                    title = getString(R.string.notif_bind_first_title),
                    key = "bind-first",
                )
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
