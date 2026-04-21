package com.tinkernorth.dish.ui.main

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.databinding.ActivityMainBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.TelemetryTracker
import com.tinkernorth.dish.util.WakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener,
    SlotActionListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var controllerAdapter: ControllerAdapter
    private lateinit var inputManager: InputManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    // Gamepad input, telemetry, wake-lock, low-power
    private val inputProcessor = GamepadInputProcessor()
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var lowPowerManager: LowPowerManager
    private lateinit var telemetryTracker: TelemetryTracker

    // Which slot is currently doing a BT permission/profile flow
    private var pendingBtSlotId: String? = null
    // Per-slot listener references so we can detach/replace cleanly on restart.
    private val btListeners = mutableMapOf<String, BluetoothGamepad.Listener>()

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val slotId = pendingBtSlotId ?: return@registerForActivityResult
        if (results.values.all { it }) {
            showProfilePicker(slotId)
        } else {
            viewModel.onBtError(slotId, "Bluetooth permissions denied")
        }
    }

    private val btDiscoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val slotId = pendingBtSlotId ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_CANCELED) {
            viewModel.onBtError(slotId, "Discoverability denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        controllerAdapter = ControllerAdapter(this)

        setupUI()
        setupManagers()
        observeViewModel()
        tryAutoReconnectBt()
    }

    // ═══════════════════════════════════════════════════════════
    //  UI SETUP
    // ═══════════════════════════════════════════════════════════

    private fun setupUI() {
        binding.rvControllers.adapter = controllerAdapter
        initDot(binding.dotServer)
    }

    private fun initDot(v: View) {
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.colorMuted)) }
    }

    private fun setDot(v: View, colorRes: Int) {
        (v.background as? GradientDrawable)?.setColor(getColor(colorRes))
    }

    private fun setupManagers() {
        // Wire physical gamepad input → find the slot for that device and send to its destination
        inputProcessor.reportSender =
            GamepadInputProcessor.ReportSender { wButtons, bLT, bRT, sLX, sLY, sRX, sRY ->
                val s = viewModel.uiState.value
                // Send to every connected physical slot
                for (slot in s.physicalSlots.filter { it.isConnected }) {
                    when (slot.destType) {
                        SlotDestType.WIFI -> viewModel.serverManager.controllerRepo.sendReport(
                            0, wButtons, bLT, bRT, sLX, sLY, sRX, sRY
                        )
                        SlotDestType.BLUETOOTH -> btRegistry.sendReport(
                            slot.id,
                            buildXInputReport(wButtons, bLT, bRT, sLX, sLY, sRX, sRY),
                        )
                        SlotDestType.NONE -> {}
                    }
                }
            }

        // Wake lock
        wakeLockManager = WakeLockManager(this, window)
        wakeLockManager.views = WakeLockManager.Views(
            tvScreenLock = binding.tvScreenLock,
            tvWakeLock = binding.tvWakeLock,
        )

        // Low-power
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views = LowPowerManager.Views(
            llCountdownBanner = findViewById(R.id.llCountdownBanner),
            tvCountdownSeconds = findViewById(R.id.tvCountdownSeconds),
            flLowPowerOverlay = findViewById(R.id.flLowPowerOverlay),
            tvLowPowerTime = findViewById(R.id.tvLowPowerTime),
            tvLowPowerStatus = findViewById(R.id.tvLowPowerStatus),
        )
        lowPowerManager.activeControllerCount = { viewModel.uiState.value.slots.count { it.isConnected } }
        wakeLockManager.onLockStateChanged = { lowPowerManager.onLockStateChanged(it) }

        // Telemetry
        telemetryTracker = TelemetryTracker(inputProcessor)
        telemetryTracker.start()
    }

    // ═══════════════════════════════════════════════════════════
    //  OBSERVE + UPDATE
    // ═══════════════════════════════════════════════════════════

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect { updateUI(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.events.collect { handleEvent(it) } }
        }
    }

    private fun updateUI(s: MainUiState) {
        // Status bar
        val anyConn = s.anyConnected
        setDot(binding.dotServer, if (anyConn) R.color.colorSuccess else R.color.colorMuted)
        val connSlots = s.slots.filter { it.isConnected }
        binding.tvServerStatus.text = when {
            connSlots.size > 1 -> "${connSlots.size} connections"
            connSlots.size == 1 -> connSlots[0].connectedName ?: "Connected"
            s.isScanning -> "Scanning…"
            else -> "No active connections"
        }
        binding.tvServerStatus.setTextColor(getColor(if (anyConn) R.color.colorSuccess else R.color.colorMuted))

        // Slot list
        controllerAdapter.submitSlots(s.slots, s.discoveredServers, s.isScanning)

        // Wake lock
        wakeLockManager.update(anyConn)
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowToast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            is MainEvent.ShowPairingDialog -> showPairingDialog(event.server)
            is MainEvent.RequestBluetoothPermissions -> {
                pendingBtSlotId = event.slotId
                requestBtPermissions()
            }
            is MainEvent.RequestDiscoverable -> {
                pendingBtSlotId = event.slotId
                requestDiscoverable()
            }
        }
    }

    private fun showPairingDialog(server: DiscoveredServer) {
        val v = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val etPin = v.findViewById<EditText>(R.id.et_pin)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(v)
            .setPositiveButton("Connect") { _: DialogInterface, _: Int ->
                viewModel.onPairingPinEntered(server, etPin.text.toString().ifEmpty { "0000" })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════
    //  SLOT ACTION LISTENER
    // ═══════════════════════════════════════════════════════════

    override fun onSlotTapped(slotId: String) = viewModel.toggleSlotExpanded(slotId)
    override fun onDestWifi(slotId: String) = viewModel.setSlotDestWifi(slotId)
    override fun onDestBt(slotId: String) = viewModel.setSlotDestBt(slotId)
    override fun onScan(slotId: String) = viewModel.onScanClicked()
    override fun onServerSelected(slotId: String, server: DiscoveredServer) = viewModel.onServerSelected(slotId, server)
    override fun onBtStart(slotId: String) {
        pendingBtSlotId = slotId
        requestBtPermissions()
    }
    override fun onDisconnect(slotId: String) {
        btListeners.remove(slotId)?.let { btRegistry.removeListener(slotId, it) }
        btRegistry.stop(slotId)
        // User-initiated disconnect: forget the host so we don't auto-reconnect next launch.
        btRegistry.forgetHost(slotId)
        viewModel.disconnectSlot(slotId)
    }
    override fun onOpenGamepad() {
        val vSlot = viewModel.uiState.value.virtualSlot
        val intent = Intent(this, GamepadOverlayActivity::class.java).apply {
            putExtra(GamepadOverlayActivity.EXTRA_DEST_TYPE, vSlot.destType.name)
            putExtra(GamepadOverlayActivity.EXTRA_USE_PS_LAYOUT, vSlot.usePlayStationLayout)
        }
        startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════
    //  BLUETOOTH
    // ═══════════════════════════════════════════════════════════

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) { btPermissionLauncher.launch(needed.toTypedArray()); return }
        }
        showProfilePicker(pendingBtSlotId ?: return)
    }

    private fun showProfilePicker(slotId: String) {
        val profiles = BluetoothGamepad.GamepadProfile.entries
        val names = profiles.map { it.profileName }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Controller Profile")
            .setItems(names) { _, which ->
                startBtGamepad(slotId, profiles[which], autoConnectMac = null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Starts a [BluetoothGamepad] for [slotId] with [profile] via the singleton
     * registry. When [autoConnectMac] is non-null we're in silent auto-reconnect
     * mode: the Discoverable prompt is suppressed and the gamepad tries to
     * re-establish the link directly.
     */
    private fun startBtGamepad(
        slotId: String,
        profile: BluetoothGamepad.GamepadProfile,
        autoConnectMac: String?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        viewModel.setBtProfile(slotId, profile.profileName)
        attachBtListener(slotId)
        btRegistry.start(slotId, profile, autoConnectMac)
    }

    /**
     * Ensures a single listener is attached to the registry for [slotId] that
     * forwards events to the [MainViewModel]. Replaces any previously-attached
     * listener for the same slot so restarts don't leak duplicates.
     */
    private fun attachBtListener(slotId: String) {
        btListeners.remove(slotId)?.let { btRegistry.removeListener(slotId, it) }
        val listener = object : BluetoothGamepad.Listener {
            override fun onRegistered() = runOnUiThread {
                val silent = btRegistry.isAutoReconnecting(slotId)
                viewModel.onBtRegistered(slotId, requestDiscoverable = !silent)
            }
            override fun onUnregistered() = runOnUiThread { viewModel.onBtDisconnected(slotId) }
            override fun onConnected(device: BluetoothDevice) = runOnUiThread {
                viewModel.onBtConnected(slotId, device.name ?: "Unknown")
            }
            override fun onDisconnected(device: BluetoothDevice) = runOnUiThread {
                viewModel.onBtDisconnected(slotId)
            }
            override fun onError(message: String) = runOnUiThread {
                viewModel.onBtError(slotId, message)
            }
        }
        btRegistry.addListener(slotId, listener)
        btListeners[slotId] = listener
    }

    /**
     * If the last successful BT host is on record, silently re-register and
     * reconnect the owning slot on launch. Only attempts the virtual slot
     * (always present); physical slots depend on a transient InputDevice id and
     * aren't safe to restore here.
     */
    private fun tryAutoReconnectBt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!hasBluetoothPermissions()) return
        if (btRegistry.isActive(VIRTUAL_SLOT_ID)) return
        attachBtListener(VIRTUAL_SLOT_ID)
        val profile = btRegistry.tryAutoReconnect(VIRTUAL_SLOT_ID)
        if (profile == null) {
            btListeners.remove(VIRTUAL_SLOT_ID)?.let { btRegistry.removeListener(VIRTUAL_SLOT_ID, it) }
            return
        }
        viewModel.setSlotDestBt(VIRTUAL_SLOT_ID)
        viewModel.setBtProfile(VIRTUAL_SLOT_ID, profile.profileName)
    }

    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        }
        btDiscoverableLauncher.launch(intent)
    }

    // ═══════════════════════════════════════════════════════════
    //  GAMEPAD INPUT DISPATCH
    // ═══════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.uiState.value.physicalSlots.any { it.isConnected }) {
            inputProcessor.handleKeyEvent(event)?.let { return it }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.uiState.value.physicalSlots.any { it.isConnected }) {
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
        if (!hasFocus) { inputProcessor.zeroAxes(); inputProcessor.trySend() }
    }

    // ═══════════════════════════════════════════════════════════
    //  INPUT DEVICE LISTENER
    // ═══════════════════════════════════════════════════════════

    override fun onResume() { super.onResume(); inputManager.registerInputDeviceListener(this, null); syncControllers() }
    override fun onPause() { super.onPause(); inputManager.unregisterInputDeviceListener(this) }
    override fun onDestroy() {
        super.onDestroy()
        // The registry is process-scoped and intentionally outlives this activity
        // so the overlay activity keeps the same HID sessions; only detach the
        // per-slot listeners we installed above.
        btListeners.forEach { (slotId, l) -> btRegistry.removeListener(slotId, l) }
        btListeners.clear()
        lowPowerManager.cancel(); telemetryTracker.stop(); wakeLockManager.release()
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
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (isGamepad(dev)) viewModel.onControllerConnected(id, dev.name)
        }
    }
    private fun isGamepad(d: InputDevice): Boolean {
        if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        val s = d.sources
        val isGame = (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isGame) return false

        // Filter out touchscreens/sensors (like goodix_tso) that lack gamepad buttons
        return d.hasKeys(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        ).any { it }
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    private fun buildXInputReport(wBtn: Int, bLT: Int, bRT: Int, sLX: Int, sLY: Int, sRX: Int, sRY: Int): ByteArray {
        val r = ByteArray(14)
        r[0] = 0; r[1] = 0x14
        r[2] = (wBtn and 0xFF).toByte(); r[3] = ((wBtn shr 8) and 0xFF).toByte()
        r[4] = bLT.toByte(); r[5] = bRT.toByte()
        r[6] = (sLX and 0xFF).toByte(); r[7] = ((sLX shr 8) and 0xFF).toByte()
        r[8] = (sLY and 0xFF).toByte(); r[9] = ((sLY shr 8) and 0xFF).toByte()
        r[10] = (sRX and 0xFF).toByte(); r[11] = ((sRX shr 8) and 0xFF).toByte()
        r[12] = (sRY and 0xFF).toByte(); r[13] = ((sRY shr 8) and 0xFF).toByte()
        return r
    }
}
