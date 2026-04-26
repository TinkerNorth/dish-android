// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
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
import com.tinkernorth.dish.data.network.WifiConnection
import com.tinkernorth.dish.data.network.WifiConnectionManager
import com.tinkernorth.dish.databinding.ActivityConnectionsBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dedicated screen for managing the pool of WiFi servers and Bluetooth hosts.
 * Two sections, both living on top of [ConnectionHub]:
 *   - WiFi: scan → pair (if needed) → connect; remembered entries auto-reconnect
 *   - Bluetooth: pick profile → register HID → pair from the target host
 */
@AndroidEntryPoint
class ConnectionsActivity : AppCompatActivity() {
    @Inject lateinit var wifi: WifiConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var store: com.tinkernorth.dish.data.network.ConnectionStore

    private lateinit var binding: ActivityConnectionsBinding

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
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnWifiScan.setOnClickListener { wifi.startDiscovery() }
        binding.btnBtAdd.setOnClickListener { requestBtPermissions() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wifi.discoveredServers.collect { render(hub.connections.value) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wifi.isScanning.collect { scanning ->
                    binding.btnWifiScan.isEnabled = !scanning
                    binding.btnWifiScan.text = if (scanning) "Scanning…" else "Scan"
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wifi.events.collect { ev ->
                    when (ev) {
                        is ConnectionEvent.Error -> Toast.makeText(this@ConnectionsActivity, ev.message, Toast.LENGTH_SHORT).show()
                        is ConnectionEvent.PairingRequired -> showPairingDialog(ev.server.ip, ev.server.pairPort)
                    }
                }
            }
        }
    }

    // ── WiFi rendering ────────────────────────────────────────────────────

    private fun render(conns: List<ConnectionSummary>) {
        val wifiConns = conns.filter { it.kind == ConnectionKind.WIFI }
        val discovered = wifi.discoveredServers.value
        val knownIds = wifiConns.map { it.id }.toSet()
        val list = binding.llWifiList
        list.removeAllViews()
        wifiConns.forEach { list.addView(wifiRow(it)) }
        for (s in discovered) {
            val id = WifiConnection.idFor(s)
            if (id !in knownIds) list.addView(discoveredWifiRow(s))
        }
        binding.tvWifiEmpty.visibility =
            if (list.childCount == 0) View.VISIBLE else View.GONE

        val btConns = conns.filter { it.kind == ConnectionKind.BLUETOOTH }
        binding.llBtList.removeAllViews()
        btConns.forEach { binding.llBtList.addView(btRow(it)) }
        binding.tvBtEmpty.visibility =
            if (btConns.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun wifiRow(c: ConnectionSummary): View {
        val v = inflateRow(c.label, c.detail, statusText(c))
        val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRowAction)
        val btnSecondary = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRowSecondary)
        when (c.live) {
            ConnectionLive.CONNECTED -> {
                btn.text = "Disconnect"
                btn.setOnClickListener { wifi.disconnect(c.id) }
            }
            ConnectionLive.CONNECTING -> {
                btn.text = "Connecting…"
                btn.isEnabled = false
            }
            ConnectionLive.IDLE -> {
                btn.text = "Connect"
                btn.setOnClickListener {
                    val remembered = wifi.remembered().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    wifi.connect(remembered.toDiscovered())
                }
            }
        }
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = "Forget"
        btnSecondary.setOnClickListener { wifi.forget(c.id) }
        return v
    }

    private fun discoveredWifiRow(s: com.tinkernorth.dish.data.model.DiscoveredServer): View {
        val v = inflateRow(s.name.ifEmpty { s.ip }, "${s.ip} • UDP ${s.udpPort}", "Discovered")
        val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRowAction)
        btn.text = "Connect"
        btn.setOnClickListener { wifi.connect(s) }
        return v
    }

    private fun btRow(c: ConnectionSummary): View {
        val v = inflateRow(c.label, c.detail, statusText(c))
        val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRowAction)
        val btnSecondary = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRowSecondary)
        when (c.live) {
            ConnectionLive.CONNECTED -> {
                btn.text = "Disconnect"
                btn.setOnClickListener { btRegistry.stop(c.id) }
            }
            ConnectionLive.CONNECTING -> {
                btn.text = "Waiting…"
                btn.isEnabled = false
            }
            ConnectionLive.IDLE -> {
                btn.text = "Reconnect"
                btn.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) btRegistry.tryAutoReconnect(c.id)
                }
            }
        }
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = "Forget"
        btnSecondary.setOnClickListener {
            btRegistry.stop(c.id)
            store.forgetBt(c.id)
            render(hub.connections.value)
        }
        return v
    }

    private fun statusText(c: ConnectionSummary): String =
        when (c.live) {
            ConnectionLive.CONNECTED -> "Connected"
            ConnectionLive.CONNECTING -> "Connecting"
            ConnectionLive.IDLE -> "Idle"
        }

    private fun inflateRow(
        title: String,
        detail: String,
        status: String,
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.row_connection, binding.llWifiList, false)
        v.findViewById<TextView>(R.id.tvRowTitle).text = title
        v.findViewById<TextView>(R.id.tvRowDetail).text = detail
        v.findViewById<TextView>(R.id.tvRowStatus).text = status
        val dot = v.findViewById<View>(R.id.dotRow)
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val color =
            when {
                status == "Connected" -> R.color.colorSuccess
                status == "Connecting" -> R.color.colorPrimary
                else -> R.color.colorMuted
            }
        (dot.background as GradientDrawable).setColor(getColor(color))
        return v
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
        val v = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val etPin = v.findViewById<EditText>(R.id.et_pin)
        MaterialAlertDialogBuilder(this)
            .setView(v)
            .setPositiveButton("Connect") { _, _ ->
                val pin = etPin.text.toString().ifEmpty { "0000" }
                val server =
                    com.tinkernorth.dish.data.model.DiscoveredServer(
                        name = "",
                        ip = ip,
                        pairPort = pairPort,
                    )
                wifi.pairWithPin(server, pin)
            }.setNegativeButton("Cancel", null)
            .show()
    }
}
