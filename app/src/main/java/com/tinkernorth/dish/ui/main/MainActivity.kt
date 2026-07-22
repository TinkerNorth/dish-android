// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.lowpower.LowPowerSignal
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.source.system.LocalNetworkAccess
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.DishSpinnerDrawable
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.attachDonatePill
import com.tinkernorth.dish.ui.common.attachGamepadHost
import com.tinkernorth.dish.ui.common.wireDonateButton
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

    @Inject lateinit var hub: ConnectionCoordinator

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var usbGamepadManager: UsbGamepadManager

    @Inject lateinit var notifications: DishNotifications

    @Inject lateinit var lowPowerSignal: LowPowerSignal

    @Inject lateinit var onboarding: OnboardingPreferenceStore

    private lateinit var gamepadHost: GamepadActivityHost

    private val nav by lazy { DishNavigator(this) }

    private val connectionsSpinner by lazy {
        DishSpinnerDrawable(this, resources.getDimensionPixelSize(R.dimen.icon_battery))
    }

    private var localNetworkRequested = false

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                hub.autoReconnectAll()
            } else {
                notifications.warn(
                    glyph = R.drawable.ic_satellite_off,
                    title = getString(R.string.notif_local_network_title),
                    body = getString(R.string.notif_local_network_body),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_open),
                        ) { nav.toConnections() },
                    key = "local-network-permission",
                )
            }
        }

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
        if (!onboarding.state.value.welcomeCompleted) {
            splashHoldUntilFirstRender = false
            nav.toSetupInput()
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyPaneLayout(resources.configuration)
        gamepadHost = attachGamepadHost(binding.root, wakeState, gamepadRegistry, notifications, lowPowerSignal)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        attachDonatePill()
        wireDonateButton()
        controllerAdapter = ControllerAdapter(this)
        setupUI()
        observeViewModel()
        // Fallback splash release: happy path is updateUI()'s first emission.
        binding.root.postDelayed({ splashHoldUntilFirstRender = false }, SPLASH_HOLD_MAX_MS)
    }

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    override fun onResume() {
        super.onResume()
        if (!com.tinkernorth.dish.DishApplication.nativeLoadFailed) {
            usbGamepadManager.reconcileForeground()
            ensureLocalNetworkForReconnect()
        }
    }

    // Foreground auto-reconnect (ConnectionForegroundObserver) runs off an Activity and can't prompt,
    // so ask here — otherwise a remembered satellite fails silently on Android 17.
    private fun ensureLocalNetworkForReconnect() {
        if (localNetworkRequested || LocalNetworkAccess.isGranted(this)) return
        if (satellite.remembered().isEmpty()) return
        localNetworkRequested = true
        localNetworkPermissionLauncher.launch(LocalNetworkAccess.PERMISSION)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) applyPaneLayout(newConfig)
    }

    private fun applyPaneLayout(config: Configuration) {
        val panes = binding.llPanes ?: return
        val infoPane = binding.llInfoPane ?: return
        val controllersPane = binding.llControllersPane ?: return
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        panes.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val controllersIndex = if (landscape) 0 else 1
        if (panes.indexOfChild(controllersPane) != controllersIndex) {
            panes.removeView(controllersPane)
            panes.addView(controllersPane, controllersIndex)
        }
        infoPane.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            height = LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (landscape) 2f else 0f
        }
        controllersPane.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            height = if (landscape) LinearLayout.LayoutParams.MATCH_PARENT else 0
            weight = if (landscape) 3f else 1f
        }
    }

    private fun setupUI() {
        binding.sectionConnections.labelSection.setText(R.string.section_connections)
        binding.sectionControllers.labelSection.setText(R.string.section_controllers)
        binding.ivConnectionsLoading.setImageDrawable(connectionsSpinner)
        // The "Add a controller" invite rides after the controller list as a permanent
        // last row, so the route into setup never disappears.
        binding.rvControllers.adapter =
            ConcatAdapter(controllerAdapter, AddControllerAdapter { nav.toSetupInput() })
        binding.rvControllers.setHasFixedSize(true)
        (binding.rvControllers.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        binding.btnManageConnections.setOnClickListener { nav.toConnections() }
        binding.btnSettings.setOnClickListener { nav.toSettings() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { updateUI(it) }
            }
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
        // Unstable links are still streaming, so they count as online here just like on the connections screen.
        val liveCount = s.connections.count { it.live.isLiveLink() }
        val totalCount = s.connections.size
        val checking = s.anyConnecting
        binding.ivConnectionsLoading.isVisible = checking
        if (checking) connectionsSpinner.start() else connectionsSpinner.stop()
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
            s.touchpadBySlot,
            s.pathCards,
            s.inputRates,
            s.screenPeakHz,
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

    override fun onConfigure(slotId: String) {
        nav.toConfigureBindings(slotId)
    }

    override fun onManageDestinations() {
        nav.toConnections()
    }

    override fun onReconnect(slotId: String) {
        viewModel.reconnectHosts()
    }

    override fun onUnbind(slotId: String) {
        viewModel.unbindSlot(slotId)
    }

    override fun onOpenTouchpad(slotId: String) {
        val state = viewModel.uiState.value
        val slot = state.slots.firstOrNull { it.id == slotId } ?: return
        val cid = slot.boundConnectionId ?: return
        if (state.touchpadBySlot[slotId]?.openable != true) return
        nav.toTouchpad(connectionId = cid, slotId = slotId)
    }

    override fun onOpenGamepad(slotId: String) {
        val state = viewModel.uiState.value
        val slot = state.slots.firstOrNull { it.id == slotId } ?: return
        val cid =
            slot.boundConnectionId ?: run {
                notifications.info(
                    glyph = R.drawable.ic_gamepad,
                    title = getString(R.string.notif_bind_first_title),
                    key = "bind-first",
                )
                return
            }
        val summary = slot.boundStatus
        val usePs =
            summary?.btProfile == "PlayStation" ||
                summary?.satelliteControllerTypes?.get(slotId) ==
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
        // arrives. Chosen for "feels like an intro, not a hang": long
        // enough that the splash is perceived on a healthy device, short
        // enough that a stalled cold boot doesn't trap the user.
        const val SPLASH_HOLD_MAX_MS = 1500L
    }
}
