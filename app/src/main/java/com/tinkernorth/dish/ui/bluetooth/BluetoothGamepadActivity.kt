package com.tinkernorth.dish.ui.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityBluetoothGamepadBinding
import com.tinkernorth.dish.ui.common.GamepadTouchView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Activity for Bluetooth HID gamepad mode.
 */
@RequiresApi(Build.VERSION_CODES.P)
@AndroidEntryPoint
class BluetoothGamepadActivity :
    AppCompatActivity(),
    GamepadTouchView.Listener {

    private lateinit var binding: ActivityBluetoothGamepadBinding
    private val viewModel: BluetoothGamepadViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                viewModel.onConnectClicked(true)
            } else {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            }
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode > 0) {
                // Discoverability granted
            } else {
                Toast.makeText(this, "Discoverability denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothGamepadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        setupUI()
        observeViewModel()

        // Attempt to auto-reconnect to the last known host if we have one on
        // record and permissions are already granted. Silent no-op otherwise.
        viewModel.tryAutoStart(hasBluetoothPermissions())
    }

    private fun setupUI() {
        binding.gamepadTouchView.listener = this
        binding.btnBtBack.setOnClickListener { finish() }
        binding.btnBtConnect.setOnClickListener {
            viewModel.onConnectClicked(hasBluetoothPermissions())
        }
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

    private fun updateUI(state: BluetoothUiState) {
        binding.tvBtStatus.text = state.status
        binding.gamepadTouchView.usePlayStation = state.usePlayStationLayout

        if (state.isConnected) {
            binding.tvBtStatus.setTextColor(getColor(R.color.colorSuccess))
            binding.btnBtConnect.text = "Disconnect"
        } else {
            binding.tvBtStatus.setTextColor(getColor(R.color.colorMuted))
            binding.btnBtConnect.text = if (state.isRegistered) "Advertise" else "Connect"
        }
    }

    private fun handleEvent(event: BluetoothEvent) {
        when (event) {
            is BluetoothEvent.ShowToast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            is BluetoothEvent.RequestBluetoothPermissions -> requestBluetoothPermissions()
            is BluetoothEvent.ShowProfilePicker -> showProfilePicker()
            is BluetoothEvent.RequestDiscoverable -> makeDiscoverable()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                ),
            )
        }
    }

    private fun showProfilePicker() {
        val profiles = BluetoothGamepad.GamepadProfile.entries
        val names = profiles.map { it.profileName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Controller Profile")
            .setItems(names) { _, which ->
                viewModel.startGamepad(profiles[which])
            }.setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun makeDiscoverable() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        }
        discoverableLauncher.launch(discoverableIntent)
    }

    // ── GamepadTouchView.Listener ────────────────────────────────────────────

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        if (!viewModel.isConnected) return
        val report = viewModel.buildReport(
            buttons = state.buttons,
            hat = state.hatSwitch,
            lx = state.leftX,
            ly = state.leftY,
            rx = state.rightX,
            ry = state.rightY,
            lt = state.leftTrigger,
            rt = state.rightTrigger,
        ) ?: return
        viewModel.sendReport(report)
    }

    // ── Physical controller pass-through ────────────────────────────────────

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (!viewModel.isConnected) return super.dispatchGenericMotionEvent(event)
        if (event.source and android.view.InputDevice.SOURCE_JOYSTICK == 0) {
            return super.dispatchGenericMotionEvent(event)
        }
        sendPhysicalReport(event)
        return true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!viewModel.isConnected) return super.dispatchKeyEvent(event)
        val kc = event.keyCode
        if (!isGamepadButton(kc)) return super.dispatchKeyEvent(event)
        val bit = mapKeyToButton(kc)
        if (bit != 0) {
            physicalButtons = if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                physicalButtons or bit
            } else {
                physicalButtons and bit.inv()
            }
            sendPhysicalState()
        }
        return true
    }

    private var physicalButtons = 0

    private fun sendPhysicalReport(event: android.view.MotionEvent) {
        val lx = (event.getAxisValue(android.view.MotionEvent.AXIS_X) * 32767f).toInt().toShort()
        val ly = (event.getAxisValue(android.view.MotionEvent.AXIS_Y) * 32767f).toInt().toShort()
        val rx = (event.getAxisValue(android.view.MotionEvent.AXIS_Z) * 32767f).toInt().toShort()
        val ry = (event.getAxisValue(android.view.MotionEvent.AXIS_RZ) * 32767f).toInt().toShort()
        val lt = (event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER) * 255f).toInt()
        val rt = (event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER) * 255f).toInt()
        val hat = hatToSwitch(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y))

        val report = viewModel.buildReport(physicalButtons, hat, lx, ly, rx, ry, lt, rt) ?: return
        viewModel.sendReport(report)
    }

    private fun sendPhysicalState() {
        val report = viewModel.buildReport(physicalButtons, GamepadTouchView.HAT_NONE, 0, 0, 0, 0, 0, 0) ?: return
        viewModel.sendReport(report)
    }

    private fun hatToSwitch(x: Float, y: Float): Int = when {
        x == 0f && y == 0f -> GamepadTouchView.HAT_NONE
        x == 0f && y < 0f -> GamepadTouchView.HAT_N
        x > 0f && y < 0f -> GamepadTouchView.HAT_NE
        x > 0f && y == 0f -> GamepadTouchView.HAT_E
        x > 0f && y > 0f -> GamepadTouchView.HAT_SE
        x == 0f && y > 0f -> GamepadTouchView.HAT_S
        x < 0f && y > 0f -> GamepadTouchView.HAT_SW
        x < 0f && y == 0f -> GamepadTouchView.HAT_W
        x < 0f && y < 0f -> GamepadTouchView.HAT_NW
        else -> GamepadTouchView.HAT_NONE
    }

    private fun isGamepadButton(keyCode: Int): Boolean =
        keyCode in android.view.KeyEvent.KEYCODE_BUTTON_A..android.view.KeyEvent.KEYCODE_BUTTON_MODE ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_SELECT ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_START

    private fun mapKeyToButton(keyCode: Int): Int = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BUTTON_A -> GamepadTouchView.BTN_A
        android.view.KeyEvent.KEYCODE_BUTTON_B -> GamepadTouchView.BTN_B
        android.view.KeyEvent.KEYCODE_BUTTON_X -> GamepadTouchView.BTN_X
        android.view.KeyEvent.KEYCODE_BUTTON_Y -> GamepadTouchView.BTN_Y
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> GamepadTouchView.BTN_LB
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> GamepadTouchView.BTN_RB
        android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadTouchView.BTN_SELECT
        android.view.KeyEvent.KEYCODE_BUTTON_START -> GamepadTouchView.BTN_START
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadTouchView.BTN_LS
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadTouchView.BTN_RS
        android.view.KeyEvent.KEYCODE_BUTTON_MODE -> GamepadTouchView.BTN_HOME
        else -> 0
    }
}
