// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.attachGamepadHost
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

    @Inject lateinit var notifications: DishNotifications

    private lateinit var gamepadHost: GamepadActivityHost

    // Typed navigation surface over res/navigation/nav_graph.xml. Replaces
    // raw startActivity(Intent) calls with named, typed-args methods so a
    // mistyped extra is a compile error rather than a silent runtime
    // "extra missing" branch. See DishNavigator's KDoc for the rationale.
    private val nav by lazy { DishNavigator(this) }

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
            nav.toNativeUnavailable()
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // `attachGamepadHost` centralises the construct-and-install dance
        // every Dish activity used to inline (see ActivityExtensions.kt).
        // `install(notifications)` inside it wires the wake-state collectors
        // AND the themed notification host in one call.
        gamepadHost = attachGamepadHost(binding.root, wakeState, gamepadRegistry, notifications)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
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
        // Section header label lives in `section_header.xml` and is set
        // here so the include's TextView (id `labelSection`) carries the
        // dashboard's "CONTROLLERS" string. Icon + trailing-button slots
        // stay hidden by default for this eyebrow-only callsite.
        binding.sectionControllers.labelSection.setText(R.string.section_controllers)
        binding.rvControllers.adapter = controllerAdapter
        binding.btnManageConnections.setOnClickListener { nav.toConnections() }
        binding.btnSettings.setOnClickListener { nav.toSettings() }
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
                liveCount == 0 -> resources.getQuantityString(R.plurals.status_remembered, totalCount, totalCount)
                // Quantity is selected on the *total* count (the "of N" number):
                // "1 of 1 online" is singular, "2 of 5 online" plural — the
                // positional args are still (liveCount, totalCount) in that
                // order to match the %1$d / %2$d format slots.
                else -> resources.getQuantityString(R.plurals.status_connected_of, totalCount, liveCount, totalCount)
            }
        controllerAdapter.submitSlots(
            s.slots,
            s.connections,
            s.motionCapabilities,
            s.touchpadModesBySatellite,
        )
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
        nav.toConnectionsForPairing(connectionId)
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

    override fun onMotionEnabledChanged(
        slotId: String,
        enabled: Boolean,
    ) = viewModel.setMotionEnabled(slotId, enabled)

    override fun onChangeTouchpadMode(
        connectionId: String,
        mode: String,
    ) = viewModel.setSatelliteTouchpadMode(connectionId, mode)

    override fun onOpenTouchpad(slotId: String) {
        val state = viewModel.uiState.value
        val slot = state.slots.firstOrNull { it.id == slotId } ?: return
        val cid = slot.boundConnectionId ?: return
        val mode = state.touchpadModesBySatellite[cid] ?: return
        nav.toTouchpad(connectionId = cid, touchpadMode = mode, slotId = slotId)
    }

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
                com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
        nav.toGamepad(connectionId = cid, usePsLayout = usePs)
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
