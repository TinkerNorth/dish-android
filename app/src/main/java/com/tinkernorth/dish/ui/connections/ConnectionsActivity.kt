// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.data.network.RememberedBt
import com.tinkernorth.dish.data.network.SatelliteConnection
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.SatelliteNative
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.databinding.ActivityConnectionsBinding
import com.tinkernorth.dish.databinding.DialogPairingBinding
import com.tinkernorth.dish.databinding.OverlayLowPowerBinding
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.util.LowPowerManager
import com.tinkernorth.dish.util.LowPowerTouchGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dedicated screen for managing the pool of satellites and Bluetooth hosts.
 * Two sections, both living on top of [ConnectionHub]:
 *   - Satellites: scan → pair (if needed) → connect; remembered entries auto-reconnect
 *   - Bluetooth: pick profile → register HID → pair from the target host
 *
 * Inherits the same wake-state + dim-screen + physical-gamepad pass-through
 * wiring as [com.tinkernorth.dish.ui.main.MainActivity] and
 * [com.tinkernorth.dish.ui.main.GamepadOverlayActivity]: streaming should
 * keep the screen on regardless of which Dish screen the user happens to
 * be looking at, the dim-after-idle behaviour applies here too, and a
 * connected controller still needs to flow to the satellite instead of
 * navigating this screen via Android's fallback-action pipeline.
 */
@AndroidEntryPoint
class ConnectionsActivity : AppCompatActivity() {
    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var store: com.tinkernorth.dish.data.network.ConnectionStore

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    private lateinit var binding: ActivityConnectionsBinding
    private lateinit var lowPowerBinding: OverlayLowPowerBinding
    private lateinit var lowPowerManager: LowPowerManager
    private val lowPowerTouchGate = LowPowerTouchGate()

    private val btPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            if (results.values.all { it }) {
                showProfilePicker()
            } else {
                Toast.makeText(this, "Bluetooth permissions denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val btDiscoverableLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Discoverability denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lowPowerBinding = OverlayLowPowerBinding.bind(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSatelliteScan.setOnClickListener { satellite.startDiscovery() }
        binding.btnBtAdd.setOnClickListener { requestBtPermissions() }

        setupPower()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect { conns -> render(conns) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.discoveredServers.collect { render(hub.connections.value) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.isScanning.collect { scanning ->
                    binding.btnSatelliteScan.isEnabled = !scanning
                    binding.btnSatelliteScan.text = if (scanning) "Scanning…" else "Scan"
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect { ev ->
                    when (ev) {
                        is ConnectionEvent.Error -> Toast.makeText(this@ConnectionsActivity, ev.message, Toast.LENGTH_SHORT).show()
                        is ConnectionEvent.PairingRequired -> showPairingDialog(ev.server.ip, ev.server.pairPort)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.errors.collect { msg ->
                    Toast.makeText(this@ConnectionsActivity, "Bluetooth: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
        // FLAG_KEEP_SCREEN_ON + dim-overlay engagement are driven by the
        // process-scoped WakeStateController so this screen behaves the same
        // as MainActivity when a controller is bound to a CONNECTED session.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeState.shouldKeepScreenOn.collect(::applyScreenOn)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeState.streamingSlotCount.collect { lowPowerManager.refreshStatus() }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Kill the dim-screen state machine cleanly; the next onStart
        // re-derives it from the wake-state collector.
        lowPowerManager.cancel()
    }

    private fun setupPower() {
        lowPowerManager = LowPowerManager(window)
        lowPowerManager.views =
            LowPowerManager.Views(
                llCountdownBanner = lowPowerBinding.llCountdownBanner,
                tvCountdownSeconds = lowPowerBinding.tvCountdownSeconds,
                flLowPowerOverlay = lowPowerBinding.flLowPowerOverlay,
                tvLowPowerTime = lowPowerBinding.tvLowPowerTime,
                tvLowPowerStatus = lowPowerBinding.tvLowPowerStatus,
            )
        lowPowerManager.activeControllerCount = { wakeState.streamingSlotCount.value }
    }

    private fun applyScreenOn(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lowPowerManager.onLockStateChanged(active)
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render(conns: List<ConnectionSummary>) {
        val satConns = conns.filter { it.kind == ConnectionKind.SATELLITE }
        val discovered = satellite.discoveredServers.value
        val knownIds = satConns.map { it.id }.toSet()
        val list = binding.llSatelliteList
        list.removeAllViews()
        satConns.forEach { list.addView(satelliteRow(it)) }
        for (s in discovered) {
            val id = SatelliteConnection.idFor(s)
            if (id !in knownIds) list.addView(discoveredSatelliteRow(s))
        }
        binding.tvSatelliteEmpty.visibility =
            if (list.childCount == 0) View.VISIBLE else View.GONE

        val btConns = conns.filter { it.kind == ConnectionKind.BLUETOOTH }
        binding.llBtList.removeAllViews()
        btConns.forEach { binding.llBtList.addView(btRow(it)) }
        binding.tvBtEmpty.visibility =
            if (btConns.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun satelliteRow(c: ConnectionSummary): View {
        val rb = inflateRow(binding.llSatelliteList, c.label, c.detail, statusText(c))
        when (c.live) {
            ConnectionLive.CONNECTED -> {
                rb.btnRowAction.text = "Disconnect"
                rb.btnRowAction.setOnClickListener { satellite.disconnect(c.id) }
            }
            ConnectionLive.CONNECTING -> {
                rb.btnRowAction.text = "Connecting…"
                rb.btnRowAction.isEnabled = false
            }
            ConnectionLive.IDLE -> {
                rb.btnRowAction.text = "Connect"
                rb.btnRowAction.setOnClickListener {
                    val remembered = satellite.remembered().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    satellite.connect(remembered.toDiscovered())
                }
            }
        }
        rb.btnRowSecondary.visibility = View.VISIBLE
        rb.btnRowSecondary.text = "Forget"
        rb.btnRowSecondary.setOnClickListener { satellite.forget(c.id) }
        return rb.root
    }

    private fun discoveredSatelliteRow(s: com.tinkernorth.dish.data.model.DiscoveredServer): View {
        val rb = inflateRow(binding.llSatelliteList, s.name.ifEmpty { s.ip }, "${s.ip} • UDP ${s.udpPort}", "Discovered")
        rb.btnRowAction.text = "Connect"
        rb.btnRowAction.setOnClickListener { satellite.connect(s) }
        return rb.root
    }

    private fun btRow(c: ConnectionSummary): View {
        val rb = inflateRow(binding.llBtList, c.label, c.detail, statusText(c))
        when (c.live) {
            ConnectionLive.CONNECTED -> {
                rb.btnRowAction.text = "Disconnect"
                rb.btnRowAction.setOnClickListener { btRegistry.stop(c.id) }
            }
            ConnectionLive.CONNECTING -> {
                val state = btRegistry.state(c.id)
                rb.btnRowAction.text =
                    when {
                        state.registered -> "Pair from host"
                        state.acquiring -> "Acquiring…"
                        else -> "Waiting…"
                    }
                rb.btnRowAction.isEnabled = false
            }
            ConnectionLive.IDLE -> {
                rb.btnRowAction.text = "Reconnect"
                rb.btnRowAction.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) btRegistry.tryAutoReconnect(c.id)
                }
            }
        }
        rb.btnRowSecondary.visibility = View.VISIBLE
        // Transient rows (registration in flight, MAC not yet known) aren't in
        // rememberedBt yet — Forget there means "cancel" rather than "forget".
        val rememberedEntry = store.rememberedBt().firstOrNull { it.id == c.id }
        val isRemembered = rememberedEntry != null
        rb.btnRowSecondary.text = if (isRemembered) "Forget" else "Cancel"
        rb.btnRowSecondary.setOnClickListener {
            if (isRemembered) {
                confirmForgetBt(c.id, rememberedEntry)
            } else {
                btRegistry.stop(c.id)
                render(hub.connections.value)
            }
        }
        return rb.root
    }

    /**
     * Three-way confirmation when forgetting a remembered BT host: the system
     * pairing survives our app-level Forget, so we let the user opt to also
     * deep-link into Bluetooth settings, defer that decision, or back out
     * entirely. Forget commits only on the two non-Cancel paths.
     */
    private fun confirmForgetBt(
        id: String,
        entry: RememberedBt,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Forget ${entry.name}?")
            .setMessage(
                "Dish will stop remembering this host. The phone keeps the system-level " +
                    "pairing record — open Bluetooth settings to fully unpair.",
            ).setPositiveButton("Open settings") { _, _ ->
                commitForgetBt(id)
                openBluetoothDeviceDetails(entry.mac)
            }.setNegativeButton("Not now") { _, _ ->
                commitForgetBt(id)
            }.setNeutralButton("Cancel", null)
            .show()
    }

    private fun commitForgetBt(id: String) {
        btRegistry.stop(id)
        store.forgetBt(id)
        render(hub.connections.value)
    }

    private fun openBluetoothDeviceDetails(mac: String) {
        // Android 11+ exposes a dedicated per-device details screen with the
        // Forget button one tap away. The intent action is stable across OEM
        // surfaces; the EXTRA key is documented as "device_address" since
        // ACTION_BLUETOOTH_DEVICE_DETAILS itself isn't part of the public
        // androidx Settings constants. We don't pre-check resolveActivity()
        // because that would trigger QueryPermissionsNeeded on API 30+ and
        // settings is a system app — we just attempt and fall back on
        // ActivityNotFoundException.
        val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            startActivity(fallback)
            return
        }
        val deepLink =
            Intent("android.settings.BLUETOOTH_DEVICE_DETAILS_SETTINGS").apply {
                putExtra("device_address", mac)
                data = Uri.parse("bt-mac:$mac")
            }
        runCatching { startActivity(deepLink) }
            .onFailure { startActivity(fallback) }
    }

    private fun statusText(c: ConnectionSummary): String =
        when (c.live) {
            ConnectionLive.CONNECTED -> "Connected"
            ConnectionLive.CONNECTING -> "Connecting"
            ConnectionLive.IDLE -> "Idle"
        }

    private fun inflateRow(
        parent: ViewGroup,
        title: String,
        detail: String,
        status: String,
    ): RowConnectionBinding {
        val rb = RowConnectionBinding.inflate(layoutInflater, parent, false)
        rb.tvRowTitle.text = title
        rb.tvRowDetail.text = detail
        rb.tvRowStatus.text = status
        rb.dotRow.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val color =
            when (status) {
                "Connected" -> R.color.colorSuccess
                "Connecting" -> R.color.colorPrimary
                else -> R.color.colorMuted
            }
        (rb.dotRow.background as GradientDrawable).setColor(getColor(color))
        return rb
    }

    // ── Bluetooth add flow ────────────────────────────────────────────────

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Bluetooth HID requires Android 9+", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed =
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                btPermissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        showProfilePicker()
    }

    private fun showProfilePicker() {
        val profiles = BluetoothGamepad.GamepadProfile.entries
        val names = profiles.map { it.profileName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Controller Profile")
            .setItems(names) { _, which -> startBtRegistration(profiles[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startBtRegistration(profile: BluetoothGamepad.GamepadProfile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Use a transient id; the registry will replace it with bt:<MAC> once
        // the host actually connects and calls ConnectionStore.rememberBt().
        val tempId = "bt-pending-${System.currentTimeMillis()}"
        btRegistry.start(tempId, profile)
        // Ask the OS for discoverability so the host can see + pair with us.
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }
        btDiscoverableLauncher.launch(intent)
    }

    private fun showPairingDialog(
        ip: String,
        pairPort: Int,
    ) {
        val db = DialogPairingBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setView(db.root)
            .setPositiveButton("Connect") { _, _ ->
                val pin =
                    db.etPin.text
                        .toString()
                        .ifEmpty { "0000" }
                val server =
                    com.tinkernorth.dish.data.model.DiscoveredServer(
                        name = "",
                        ip = ip,
                        pairPort = pairPort,
                    )
                satellite.pairWithPin(server, pin)
            }.setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PHYSICAL INPUT DISPATCH
    //
    //  Same shape as MainActivity / GamepadOverlayActivity: while this screen
    //  is foreground we still need to feed physical gamepad events into the
    //  native pipeline so they reach the bound satellite/BT slot instead of
    //  being absorbed by Android's default focus-navigation handling
    //  (BUTTON_* → KEYCODE_DPAD_CENTER → click focused view → leave screen).
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isKnownGamepad = event.deviceId in gamepadRegistry.devices.value
        if (isGamepadSource(event.source) || isKnownGamepad) {
            SatelliteNative.processGamepadKeyEvent(
                event.deviceId,
                event.source,
                event.action,
                event.keyCode,
            )
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoy =
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                event.deviceId in gamepadRegistry.devices.value
        if (isJoy &&
            SatelliteNative.processGamepadMotionEvent(
                event.deviceId,
                event.source,
                event.action,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                event.getAxisValue(MotionEvent.AXIS_RX),
                event.getAxisValue(MotionEvent.AXIS_RY),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            )
        ) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Read overlay state before notifying so a DOWN that dismisses the
        // dim still wins the gate and the rest of the gesture is swallowed
        // before reaching the underlying buttons.
        val overlayActive = lowPowerManager.state == LowPowerManager.State.ACTIVE
        val consume = lowPowerTouchGate.onDispatch(ev.action, overlayActive)
        if (ev.action == MotionEvent.ACTION_DOWN && wakeState.shouldKeepScreenOn.value) {
            lowPowerManager.onUserInteraction()
        }
        return if (consume) true else super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Symmetric with MainActivity: zero every bound device on focus loss
        // so no button stays held server-side if the screen is interrupted.
        if (!hasFocus) SatelliteNative.releaseAllPhysicalReports()
    }
}
