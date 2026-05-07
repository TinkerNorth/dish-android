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
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.BondedHost
import com.tinkernorth.dish.data.network.BondedHostsRepo
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionLive
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.data.network.RememberedBt
import com.tinkernorth.dish.data.network.SatelliteConnection
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.databinding.ActivityConnectionsBinding
import com.tinkernorth.dish.databinding.DialogPairingBinding
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dedicated screen for managing the pool of satellites and Bluetooth hosts.
 * Two sections, both living on top of [ConnectionHub]:
 *   - Satellites: scan → pair (if needed) → connect; remembered entries auto-reconnect
 *   - Bluetooth: pick profile → register HID → pair from the target host
 */
@AndroidEntryPoint
class ConnectionsActivity : AppCompatActivity() {
    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var store: com.tinkernorth.dish.data.network.ConnectionStore

    @Inject lateinit var bondedHosts: BondedHostsRepo

    /**
     * Pending Claim: a bonded host the user tapped Claim on, parked here while
     * the profile picker is open. We could pass it through the dialog, but
     * MaterialAlertDialogBuilder's setItems lambda doesn't take state and an
     * activity-scoped field is the simplest seam.
     */
    private var pendingClaim: BondedHost? = null

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

    private val bondedPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ -> bondedHosts.refresh() }

    private val btDiscoverableLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Discoverability denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onResume() {
        super.onResume()
        // The bonded set isn't observable; refresh on resume so devices the
        // user (un)paired in system settings, or new bonds created elsewhere,
        // show up without requiring a screen reopen.
        bondedHosts.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSatelliteScan.setOnClickListener { satellite.startDiscovery() }
        binding.btnBtAdd.setOnClickListener { requestBtPermissions() }
        binding.btnBondedShowAll.setOnClickListener {
            val current = bondedHosts.state.value.showAll
            bondedHosts.setShowAll(!current)
        }
        binding.btnBondedGrant.setOnClickListener { requestBondedPermission() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.connections, bondedHosts.state) { conns, bonded -> conns to bonded }
                    .collect { (conns, bonded) -> render(conns, bonded) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.discoveredServers.collect { render(hub.connections.value, bondedHosts.state.value) }
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
    }

    // ── Satellite rendering ───────────────────────────────────────────────

    private fun render(
        conns: List<ConnectionSummary>,
        bonded: BondedHostsRepo.State,
    ) {
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

        renderBonded(bonded)
    }

    private fun renderBonded(state: BondedHostsRepo.State) {
        val list = binding.llBondedList
        list.removeAllViews()

        when (state.permission) {
            BondedHostsRepo.Permission.DENIED -> {
                binding.llBondedPermission.visibility = View.VISIBLE
                binding.btnBondedShowAll.visibility = View.GONE
                binding.tvBondedEmpty.visibility = View.GONE
                binding.tvBondedHint.visibility = View.GONE
                return
            }
            BondedHostsRepo.Permission.GRANTED -> {
                binding.llBondedPermission.visibility = View.GONE
                binding.btnBondedShowAll.visibility = View.VISIBLE
            }
        }

        binding.btnBondedShowAll.text = if (state.showAll) "Show likely" else "Show all"
        binding.tvBondedHint.visibility = if (state.showAll) View.VISIBLE else View.GONE

        val visible = state.visible
        visible.forEach { list.addView(bondedRow(it)) }
        binding.tvBondedEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
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
            btRegistry.stop(c.id)
            if (isRemembered) {
                store.forgetBt(c.id)
                bondedHosts.refresh()
                offerSystemUnpair(rememberedEntry)
            }
            render(hub.connections.value, bondedHosts.state.value)
        }
        return rb.root
    }

    private fun bondedRow(host: BondedHost): View {
        val rb =
            inflateRow(
                binding.llBondedList,
                host.name,
                "${host.mac} • ${host.kind.label}",
                "Paired",
            )
        rb.btnRowAction.text = "Claim"
        rb.btnRowAction.setOnClickListener { claimBondedHost(host) }
        rb.btnRowSecondary.visibility = View.VISIBLE
        rb.btnRowSecondary.text = "Unpair…"
        rb.btnRowSecondary.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unpair ${host.name}?")
                .setMessage(
                    "Dish can't remove the system-level pairing directly. " +
                        "We'll open Bluetooth settings so you can tap Forget there.",
                ).setPositiveButton("Open settings") { _, _ -> openBluetoothDeviceDetails(host.mac) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        return rb.root
    }

    private fun claimBondedHost(host: BondedHost) {
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
                pendingClaim = host
                btPermissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        pendingClaim = host
        showProfilePicker()
    }

    private fun completeClaim(
        host: BondedHost,
        profile: BluetoothGamepad.GamepadProfile,
    ) {
        val id = BluetoothGamepadRegistry.idFor(host.mac)
        store.rememberBt(
            RememberedBt(
                id = id,
                name = host.name,
                mac = host.mac,
                profileName = profile.profileName,
            ),
        )
        bondedHosts.refresh()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            btRegistry.tryAutoReconnect(id)
        }
        Toast.makeText(this, "Reconnecting to ${host.name}…", Toast.LENGTH_SHORT).show()
    }

    private fun offerSystemUnpair(entry: RememberedBt) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Also remove from system Bluetooth?")
            .setMessage(
                "Dish forgot ${entry.name}, but the phone still has a pairing record. " +
                    "Open Bluetooth settings to fully unpair.",
            ).setPositiveButton("Open settings") { _, _ -> openBluetoothDeviceDetails(entry.mac) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openBluetoothDeviceDetails(mac: String) {
        // Android 11+ exposes a dedicated per-device details screen with the
        // Forget button one tap away. The intent action is stable across OEM
        // surfaces; the EXTRA key is documented as "device_address" since
        // ACTION_BLUETOOTH_DEVICE_DETAILS itself isn't part of the public
        // androidx Settings constants.
        val deepLink =
            Intent("android.settings.BLUETOOTH_DEVICE_DETAILS_SETTINGS").apply {
                putExtra("device_address", mac)
                data = Uri.parse("bt-mac:$mac")
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            deepLink.resolveActivity(packageManager) != null
        ) {
            runCatching { startActivity(deepLink) }
                .onFailure { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        } else {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun requestBondedPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bondedHosts.refresh()
            return
        }
        val needed =
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            bondedHosts.refresh()
        } else {
            bondedPermissionLauncher.launch(needed.toTypedArray())
        }
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
        val claim = pendingClaim
        MaterialAlertDialogBuilder(this)
            .setTitle(if (claim != null) "Controller profile for ${claim.name}" else "Controller Profile")
            .setItems(names) { _, which ->
                val profile = profiles[which]
                if (claim != null) {
                    pendingClaim = null
                    completeClaim(claim, profile)
                } else {
                    startBtRegistration(profile)
                }
            }.setNegativeButton("Cancel") { _, _ -> pendingClaim = null }
            .setOnCancelListener { pendingClaim = null }
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
}
