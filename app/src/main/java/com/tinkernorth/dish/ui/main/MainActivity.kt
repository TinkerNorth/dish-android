package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.model.ControllerEntry
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.TelemetryTracker
import com.tinkernorth.dish.util.WakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val controllerAdapter = ControllerAdapter(
        onRescanClicked = { viewModel.onScanClicked() },
        onServerSelected = { server -> viewModel.onServerSelected(server) },
    )
    private lateinit var inputManager: InputManager

    // ── Gamepad input, telemetry, wake-lock, low-power ────────────────────
    private val inputProcessor = GamepadInputProcessor()
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var lowPowerManager: LowPowerManager
    private lateinit var telemetryTracker: TelemetryTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        setupUI()
        setupManagers()
        observeViewModel()
    }

    private fun setupUI() {
        binding.rvControllers.adapter = controllerAdapter

        // Init server dot as oval drawable
        initServerDot()

        binding.btnDisconnectAll.setOnClickListener {
            viewModel.onDisconnectClicked()
        }

        binding.btnBluetoothGamepad.setOnClickListener {
            val intent = android.content.Intent(
                this,
                com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadActivity::class.java
            )
            startActivity(intent)
        }
    }

    private fun initServerDot() {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(getColor(R.color.colorMuted))
        binding.dotServer.background = d
    }

    private fun setupManagers() {
        // Wire input processor to send reports through the server connection
        inputProcessor.reportSender =
            GamepadInputProcessor.ReportSender { wButtons, bLT, bRT, sLX, sLY, sRX, sRY ->
                val state = viewModel.uiState.value
                if (!state.isConnected) return@ReportSender
                val activeController = state.controllers.firstOrNull { !it.isDisconnected }
                    ?: return@ReportSender
                viewModel.serverManager.controllerRepo.sendReport(
                    activeController.controllerIndex, wButtons, bLT, bRT, sLX, sLY, sRX, sRY
                )
            }

        // Wake lock manager
        wakeLockManager = WakeLockManager(this, window)
        wakeLockManager.views = WakeLockManager.Views(
            tvScreenLock = binding.tvScreenLock,
            tvWakeLock = binding.tvWakeLock,
        )

        // Low-power manager
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views = LowPowerManager.Views(
            llCountdownBanner = binding.llCountdownBanner,
            tvCountdownSeconds = binding.tvCountdownSeconds,
            flLowPowerOverlay = binding.flLowPowerOverlay,
            tvLowPowerTime = binding.tvLowPowerTime,
            tvLowPowerStatus = binding.tvLowPowerStatus,
        )
        lowPowerManager.activeControllerCount = {
            viewModel.uiState.value.controllers.count { !it.isDisconnected }
        }
        wakeLockManager.onLockStateChanged = { active ->
            lowPowerManager.onLockStateChanged(active)
        }

        // Telemetry tracker
        telemetryTracker = TelemetryTracker(inputProcessor)
        telemetryTracker.views = TelemetryTracker.Views(
            tvEventRate = binding.tvTelEventRate,
            tvSampleRate = binding.tvTelSampleRate,
            tvSendRate = binding.tvTelSendRate,
            tvTotalSent = binding.tvTelTotalSent,
            tvLX = binding.tvTelLX,
            tvLY = binding.tvTelLY,
            tvRX = binding.tvTelRX,
            tvRY = binding.tvTelRY,
            tvLT = binding.tvTelLT,
            tvRT = binding.tvTelRT,
            tvBtns = binding.tvTelBtns,
        )
        telemetryTracker.start()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    private fun updateUI(state: MainUiState) {
        // Server status
        val statusColor = if (state.isConnected) R.color.colorSuccess else R.color.colorMuted
        (binding.dotServer.background as? GradientDrawable)?.setColor(getColor(statusColor))

        binding.tvServerStatus.text = when {
            state.isConnected -> state.connectedServerName ?: "CONNECTED"
            state.isScanning -> "SCANNING…"
            else -> "NOT CONNECTED"
        }
        binding.tvServerStatus.setTextColor(getColor(statusColor))

        // Empty state vs controller list
        binding.llEmptyState.visibility =
            if (state.controllers.isEmpty()) View.VISIBLE else View.GONE
        binding.rvControllers.visibility =
            if (state.controllers.isEmpty()) View.GONE else View.VISIBLE

        binding.btnDisconnectAll.visibility =
            if (state.isConnected) View.VISIBLE else View.GONE

        // Telemetry card — visible only when connected
        binding.cardTelemetry.visibility =
            if (state.isConnected) View.VISIBLE else View.GONE

        // Update controller list
        controllerAdapter.submitList(state.controllers, state.isConnected, state.discoveredServers, state.isScanning)

        // Wake / screen locks
        val hasActive = state.controllers.any { !it.isDisconnected }
        wakeLockManager.update(state.isConnected && hasActive)
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowToast -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            is MainEvent.ShowPairingDialog -> {
                showPairingDialog(event.server)
            }
        }
    }

    private fun showPairingDialog(server: DiscoveredServer) {
        val view = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val etPin = view.findViewById<EditText>(R.id.et_pin)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("Connect") { _: DialogInterface, _: Int ->
                val pin = etPin.text.toString().ifEmpty { "0000" }
                viewModel.onPairingPinEntered(server, pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Gamepad input dispatch ────────────────────────────────────────────
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        inputProcessor.handleKeyEvent(event)?.let { return it }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        inputProcessor.handleMotionEvent(event)?.let { return it }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Catch screen touches for low-power mode inactivity. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && wakeLockManager.isActive) {
            lowPowerManager.onUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            inputProcessor.zeroAxes()
            inputProcessor.trySend()
        }
    }

    // ── InputDevice listener ──────────────────────────────────────────────
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
        val device = InputDevice.getDevice(deviceId) ?: return
        if (isGamepad(device)) {
            viewModel.onControllerConnected(deviceId, device.name)
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        viewModel.onControllerDisconnected(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId)
        val stillGamepad = dev != null && isGamepad(dev)
        if (!stillGamepad) {
            viewModel.onControllerDisconnected(deviceId)
        }
    }

    private fun syncControllers() {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isGamepad(device)) {
                viewModel.onControllerConnected(id, device.name)
            }
        }
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
}
