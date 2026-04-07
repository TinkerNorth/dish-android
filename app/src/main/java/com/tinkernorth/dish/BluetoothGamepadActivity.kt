package com.tinkernorth.dish

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.databinding.ActivityBluetoothGamepadBinding

/**
 * Activity for Bluetooth HID gamepad mode.
 *
 * Displays an on-screen touch gamepad and sends HID reports over Bluetooth
 * to a connected host device (PC, console, tablet, etc.).
 *
 * Supports Xbox and PlayStation controller profiles.
 */
@Suppress("TooManyFunctions")
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothGamepadActivity :
    AppCompatActivity(),
    BluetoothGamepad.Listener,
    GamepadTouchView.Listener {
    private lateinit var binding: ActivityBluetoothGamepadBinding
    private var gamepad: BluetoothGamepad? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                showProfilePicker()
            } else {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothGamepadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        binding.gamepadTouchView.listener = this
        binding.btnBtBack.setOnClickListener { finish() }
        binding.btnBtConnect.setOnClickListener { onConnectClicked() }

        updateStatus("Ready — tap Connect to start")
    }

    override fun onDestroy() {
        super.onDestroy()
        gamepad?.stop()
        gamepad = null
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

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun onConnectClicked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Bluetooth gamepad requires Android 9+", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        if (gamepad?.isConnected == true) {
            gamepad?.disconnect()
            return
        }
        if (gamepad?.isRegistered == true) {
            makeDiscoverable()
            return
        }
        showProfilePicker()
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

    // ── Profile & Device Pickers ─────────────────────────────────────────────

    private fun showProfilePicker() {
        val profiles = BluetoothGamepad.GamepadProfile.entries
        val names = profiles.map { it.profileName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Controller Profile")
            .setItems(names) { _, which ->
                startGamepad(profiles[which])
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun startGamepad(profile: BluetoothGamepad.GamepadProfile) {
        gamepad?.stop()
        gamepad = BluetoothGamepad(this, this)
        binding.gamepadTouchView.usePlayStation =
            profile == BluetoothGamepad.GamepadProfile.PLAYSTATION
        gamepad?.start(profile)
        updateStatus("Registering as ${profile.profileName}…")
    }

    @SuppressLint("MissingPermission")
    private fun makeDiscoverable() {
        val discoverableIntent =
            android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SEC)
            }
        discoverableLauncher.launch(discoverableIntent)
    }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode > 0) {
                updateStatus("Discoverable for ${result.resultCode}s — pair from your PC/console")
                binding.btnBtConnect.text = "Waiting…"
            } else {
                updateStatus("Discoverability denied — tap Advertise to try again")
            }
        }

    // ── BluetoothGamepad.Listener ────────────────────────────────────────────

    override fun onRegistered() {
        runOnUiThread {
            val profile = gamepad?.currentProfile?.profileName ?: "Unknown"
            updateStatus("Registered as $profile — tap Advertise to be discoverable")
            binding.btnBtConnect.text = "Advertise"
            // Automatically prompt discoverability on first registration
            makeDiscoverable()
        }
    }

    override fun onUnregistered() {
        runOnUiThread {
            updateStatus("Unregistered")
            binding.btnBtConnect.text = "Start"
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnected(device: BluetoothDevice) {
        runOnUiThread {
            val name = device.name ?: device.address
            updateStatus("Connected to $name")
            binding.tvBtStatus.setTextColor(getColor(R.color.colorSuccess))
            binding.btnBtConnect.text = "Disconnect"
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDisconnected(device: BluetoothDevice) {
        runOnUiThread {
            updateStatus("Disconnected from ${device.name ?: device.address}")
            binding.tvBtStatus.setTextColor(getColor(R.color.colorMuted))
            binding.btnBtConnect.text = "Advertise"
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            updateStatus("Error: $message")
        }
    }

    // ── GamepadTouchView.Listener ────────────────────────────────────────────

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        val gp = gamepad ?: return
        if (!gp.isConnected) return
        android.util.Log.d(
            TAG,
            "TOUCH  LX=${state.leftX} LY=${state.leftY} RX=${state.rightX} RY=${state.rightY}" +
                " btns=0x${state.buttons.toString(16)} hat=${state.hatSwitch}" +
                " lt=${state.leftTrigger} rt=${state.rightTrigger}",
        )
        val report =
            gp.buildReport(
                buttons = state.buttons,
                hatSwitch = state.hatSwitch,
                leftX = state.leftX,
                leftY = state.leftY,
                rightX = state.rightX,
                rightY = state.rightY,
                leftTrigger = state.leftTrigger,
                rightTrigger = state.rightTrigger,
            )
        logReport("TOUCH", report)
        gp.sendReport(report)
    }

    // ── Physical controller pass-through ────────────────────────────────────

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (gamepad?.isConnected != true) return super.dispatchGenericMotionEvent(event)
        if (event.source and android.view.InputDevice.SOURCE_JOYSTICK == 0) {
            return super.dispatchGenericMotionEvent(event)
        }
        sendPhysicalReport(event)
        return true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (gamepad?.isConnected != true) return super.dispatchKeyEvent(event)
        val kc = event.keyCode
        if (!isGamepadButton(kc)) return super.dispatchKeyEvent(event)
        val bit = mapKeyToButton(kc)
        if (bit != 0) {
            physicalButtons =
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    physicalButtons or bit
                } else {
                    physicalButtons and bit.inv()
                }
            sendPhysicalState()
        }
        return true
    }

    private var physicalButtons = 0

    @Suppress("MagicNumber")
    private fun sendPhysicalReport(event: android.view.MotionEvent) {
        val rawLX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
        val rawLY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
        val rawRX = event.getAxisValue(android.view.MotionEvent.AXIS_Z)
        val rawRY = event.getAxisValue(android.view.MotionEvent.AXIS_RZ)
        val rawLT = event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER)
        val rawRT = event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER)
        val lx = (rawLX * SHORT_MAX).toInt().toShort()
        val ly = (rawLY * SHORT_MAX).toInt().toShort()
        val rx = (rawRX * SHORT_MAX).toInt().toShort()
        val ry = (rawRY * SHORT_MAX).toInt().toShort()
        val lt = (rawLT * BYTE_MAX).toInt()
        val rt = (rawRT * BYTE_MAX).toInt()
        val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
        val hat = hatToSwitch(hatX, hatY)

        android.util.Log.d(
            TAG,
            "PHYS raw  X=$rawLX Y=$rawLY Z=$rawRX RZ=$rawRY LT=$rawLT RT=$rawRT hatX=$hatX hatY=$hatY",
        )
        android.util.Log.d(
            TAG,
            "PHYS scaled  LX=$lx LY=$ly RX=$rx RY=$ry lt=$lt rt=$rt hat=$hat btns=0x${physicalButtons.toString(16)}",
        )

        val gp = gamepad ?: return
        val report = gp.buildReport(physicalButtons, hat, lx, ly, rx, ry, lt, rt)
        logReport("PHYS", report)
        gp.sendReport(report)
    }

    private fun sendPhysicalState() {
        val gp = gamepad ?: return
        val report = gp.buildReport(physicalButtons, GamepadTouchView.HAT_NONE, 0, 0, 0, 0, 0, 0)
        gp.sendReport(report)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun hatToSwitch(
        x: Float,
        y: Float,
    ): Int =
        when {
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

    @Suppress("CyclomaticComplexity")
    private fun mapKeyToButton(keyCode: Int): Int =
        when (keyCode) {
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

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun updateStatus(text: String) {
        binding.tvBtStatus.text = text
    }

    /** Log the 13-byte HID report as hex + decoded Y values. */
    @Suppress("MagicNumber")
    private fun logReport(src: String, report: ByteArray) {
        val hex = report.joinToString(" ") { "%02X".format(it) }
        // Decode leftY from bytes [5..6] little-endian signed
        val lyLo = report[5].toInt() and 0xFF
        val lyHi = report[6].toInt() and 0xFF
        val leftY = (lyHi shl 8 or lyLo).toShort()
        // Decode rightY from bytes [9..10]
        val ryLo = report[9].toInt() and 0xFF
        val ryHi = report[10].toInt() and 0xFF
        val rightY = (ryHi shl 8 or ryLo).toShort()
        android.util.Log.d(TAG, "$src REPORT [$hex]  decoded LY=$leftY RY=$rightY")
    }

    companion object {
        private const val TAG = "BtGamepad"
        private const val SHORT_MAX = 32767f
        private const val BYTE_MAX = 255f
        private const val DISCOVERABLE_DURATION_SEC = 120
    }
}
