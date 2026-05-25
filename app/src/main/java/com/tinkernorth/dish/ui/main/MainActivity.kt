// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

    private val nav by lazy { DishNavigator(this) }

    // Held by installSplashScreen()'s keep-on-screen gate; cleared either by the first
    // MainUiState render or the SPLASH_HOLD_MAX_MS fallback so a stalled ViewModel can't pin
    // the splash forever.
    @Volatile
    private var splashHoldUntilFirstRender = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so core-splashscreen can flip the
        // activity theme from Theme.App.Starting (declared in the manifest)
        // back to Theme.Dish (postSplashScreenTheme) before any content view
        // inflates against the wrong attrs. Same call also wires the system
        // splash window so the launcher icon stays on screen until the
        // keep-on-screen condition below releases it.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { splashHoldUntilFirstRender }
        super.onCreate(savedInstanceState)
        // GameActivity loads native code on touch; route to fallback before JNI surface is hit.
        if (com.tinkernorth.dish.DishApplication.nativeLoadFailed) {
            // Release the splash hold immediately: this activity is finishing
            // and the NativeUnavailable screen needs to draw itself.
            splashHoldUntilFirstRender = false
            nav.toNativeUnavailable()
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost = attachGamepadHost(binding.root, wakeState, gamepadRegistry, notifications)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        controllerAdapter = ControllerAdapter(this)
        setupUI()
        observeViewModel()
        // Fallback splash release — happy path is updateUI()'s first emission.
        binding.root.postDelayed({ splashHoldUntilFirstRender = false }, SPLASH_HOLD_MAX_MS)
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    private fun setupUI() {
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
        // First emission has been rendered: release the splash hold so the
        // system splash exits and MainActivity becomes interactive. Idempotent
        // (the postDelayed safety net may also flip this) so subsequent
        // emissions are no-ops.
        splashHoldUntilFirstRender = false
        val liveCount = s.connections.count { it.live == LinkState.Connected }
        val totalCount = s.connections.size
        binding.tvConnectionsSummary.text =
            when {
                liveCount == 0 && totalCount == 0 -> getString(R.string.status_tap_manage)
                liveCount == 0 -> resources.getQuantityString(R.plurals.status_remembered, totalCount, totalCount)
                // Quantity selects on totalCount; args order (liveCount, totalCount) matches %1$d/%2$d.
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
            is MainEvent.ShowToast ->
                notifications.warn(
                    title = event.message,
                    glyph = R.drawable.ic_satellite_off,
                )
            is MainEvent.ShowPairingDialog ->
                notifications.warn(
                    glyph = R.drawable.ic_satellite_off,
                    title = getString(R.string.notif_pairing_needed_title),
                    body = getString(R.string.notif_pairing_needed_body, event.connectionId),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_open),
                        ) { openConnectionsForPairing(event.connectionId) },
                    // Same-key replacement so repeat pair-required events for the same id don't stack.
                    key = "pair-required:${event.connectionId}",
                )
        }
    }

    private fun openConnectionsForPairing(connectionId: String) {
        nav.toConnectionsForPairing(connectionId)
    }

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
                notifications.info(
                    glyph = R.drawable.ic_gamepad,
                    title = getString(R.string.notif_bind_first_title),
                    key = "bind-first",
                )
                return
            }
        val summary = v.boundStatus
        val usePs =
            summary?.btProfile == "PlayStation" ||
                summary?.satelliteControllerTypes?.get(VIRTUAL_SLOT_ID) ==
                com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
        nav.toGamepad(connectionId = cid, usePsLayout = usePs)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    private companion object {
        // Upper bound on how long the system splash is held past the
        // installSplashScreen() call, even if the first MainUiState never
        // arrives. Chosen for "feels like an intro, not a hang" — long
        // enough that the splash is perceived on a healthy device, short
        // enough that a stalled cold boot doesn't trap the user.
        const val SPLASH_HOLD_MAX_MS = 1500L
    }
}
