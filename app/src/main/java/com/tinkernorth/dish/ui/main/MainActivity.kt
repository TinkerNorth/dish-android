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
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.TelemetryTracker
import com.tinkernorth.dish.util.WakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener, SlotActionListener {

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
        autoReconnect()
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
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(getColor(R.color.colorMuted))
        }
    }
    private fun setDot(v: View, colorRes: Int) {
        (v.background as? GradientDrawable)?.setColor(getColor(colorRes))
    }

    private fun setupManagers() {
        inputProcessor.reportSender =
            GamepadInputProcessor.ReportSender { wButtons, bLT, bRT, sLX, sLY, sRX, sRY ->
                val s = viewModel.uiState.value
                for (slot in s.physicalSlots) {
                    val cid = slot.boundConnectionId ?: continue
                    val summary = slot.boundStatus ?: continue
                    if (summary.live != ConnectionLive.CONNECTED) continue
                    when (summary.kind) {
                        ConnectionKind.WIFI ->
                            wifi.get(cid)?.sendReport(wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
                        ConnectionKind.BLUETOOTH -> {
                            // The low nibble of wButtons encodes the d-pad (see
                            // GamepadInputProcessor); split it out into the HID
                            // hat switch that the Bluetooth descriptor expects.
                            val hat = dpadToHat(wButtons and 0x000F)
                            val hidButtons = wButtons and 0x000F.inv()
                            val report = btRegistry.buildReport(
                                cid, hidButtons, hat,
                                sLX.toShort(), sLY.toShort(), sRX.toShort(), sRY.toShort(),
                                bLT, bRT,
                            ) ?: return@ReportSender
                            btRegistry.sendReport(cid, report)
                        }
                    }
                }
            }

        wakeLockManager = WakeLockManager(this, window)
        wakeLockManager.views = WakeLockManager.Views(
            tvScreenLock = binding.tvScreenLock, tvWakeLock = binding.tvWakeLock,
        )
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views = LowPowerManager.Views(
            llCountdownBanner = findViewById(R.id.llCountdownBanner),
            tvCountdownSeconds = findViewById(R.id.tvCountdownSeconds),
            flLowPowerOverlay = findViewById(R.id.flLowPowerOverlay),
            tvLowPowerTime = findViewById(R.id.tvLowPowerTime),
            tvLowPowerStatus = findViewById(R.id.tvLowPowerStatus),
        )
        lowPowerManager.activeControllerCount = {
            viewModel.uiState.value.connections.count { it.live == ConnectionLive.CONNECTED }
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
        binding.tvServerStatus.text = when {
            liveCount > 1 -> "$liveCount active connections"
            liveCount == 1 -> s.connections.first { it.live == ConnectionLive.CONNECTED }.label
            s.connections.isNotEmpty() -> "${s.connections.size} remembered"
            else -> "No connections yet"
        }
        binding.tvServerStatus.setTextColor(
            getColor(if (liveCount > 0) R.color.colorSuccess else R.color.colorMuted)
        )
        binding.tvConnectionsSummary.text = when {
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
            is MainEvent.ShowPairingDialog -> Toast.makeText(
                this, "Pairing needed — open Connections", Toast.LENGTH_LONG,
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
    override fun onBind(slotId: String, connectionId: String) = viewModel.bindSlot(slotId, connectionId)
    override fun onUnbind(slotId: String) = viewModel.unbindSlot(slotId)
    override fun onOpenGamepad() {
        val v = viewModel.uiState.value.virtualSlot
        val cid = v.boundConnectionId ?: run {
            Toast.makeText(this, "Bind a connection first", Toast.LENGTH_SHORT).show(); return
        }
        val summary = v.boundStatus
        val intent = Intent(this, GamepadOverlayActivity::class.java).apply {
            putExtra(GamepadOverlayActivity.EXTRA_CONNECTION_ID, cid)
            putExtra(GamepadOverlayActivity.EXTRA_USE_PS_LAYOUT, summary?.btProfile == "PlayStation")
        }
        startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AUTO-RECONNECT ON LAUNCH
    // ═══════════════════════════════════════════════════════════════════════

    private fun autoReconnect() {
        // The hub reads straight from persisted [ConnectionStore] so this is
        // immune to the race between its async `combine` collector and
        // `onCreate` — which was silently dropping BT reconnects on cold start
        // after a process kill.
        hub.autoReconnectAll()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DISPATCH (physical gamepads)
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.uiState.value.physicalSlots.any { it.boundStatus?.live == ConnectionLive.CONNECTED }) {
            inputProcessor.handleKeyEvent(event)?.let { return it }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.uiState.value.physicalSlots.any { it.boundStatus?.live == ConnectionLive.CONNECTED }) {
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

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT DEVICE LISTENER
    // ═══════════════════════════════════════════════════════════════════════

    override fun onResume() { super.onResume(); inputManager.registerInputDeviceListener(this, null); syncControllers() }
    override fun onPause() { super.onPause(); inputManager.unregisterInputDeviceListener(this) }
    override fun onDestroy() {
        super.onDestroy()
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
        return d.hasKeys(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        ).any { it }
    }

    /**
     * Maps the d-pad bitmask used by [GamepadInputProcessor] (bit 0=up,
     * 1=down, 2=left, 3=right) to the HID hat-switch values 1..8 expected by
     * the Bluetooth descriptor (1=N, 3=E, 5=S, 7=W, 0=neutral).
     */
    private fun dpadToHat(dpadBits: Int): Int {
        val up = dpadBits and 0x1 != 0
        val down = dpadBits and 0x2 != 0
        val left = dpadBits and 0x4 != 0
        val right = dpadBits and 0x8 != 0
        return when {
            up && right -> 2
            right && down -> 4
            down && left -> 6
            left && up -> 8
            up -> 1
            right -> 3
            down -> 5
            left -> 7
            else -> 0
        }
    }
}
