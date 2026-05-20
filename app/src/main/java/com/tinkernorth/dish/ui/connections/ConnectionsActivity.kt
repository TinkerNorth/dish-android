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
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.data.network.LinkState
import com.tinkernorth.dish.data.network.RememberedBt
import com.tinkernorth.dish.data.network.SatelliteConnection
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.databinding.ActivityConnectionsBinding
import com.tinkernorth.dish.databinding.DialogPairingBinding
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.util.GamepadActivityHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dedicated screen for managing the pool of satellites and Bluetooth hosts.
 * Two sections, both living on top of [ConnectionHub]:
 *   - Satellites: scan → pair (if needed) → connect; remembered entries auto-reconnect
 *   - Bluetooth: pick profile → register HID → pair from the target host
 *
 * Wake-lock state, dim-after-idle, and physical-gamepad pass-through all
 * live in [GamepadActivityHost] — this activity only owns the
 * connection-management UI.
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
    private lateinit var gamepadHost: GamepadActivityHost

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
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install() }
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSatelliteScan.setOnClickListener { satellite.startDiscovery() }
        binding.btnBtAdd.setOnClickListener { requestBtPermissions() }

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
                    // In-button loader: spinner accompanies the "Scanning…" label
                    // and the button drops to 0.4 alpha while disabled, per the
                    // design spec (ds-components.jsx Button) and dish-mac's
                    // DishOutlinedButtonStyle. Mirrors the SwiftUI:
                    //     Button { if isScanning { DishSpinner; Text("Scanning…") } else { Text("Scan") } }
                    //       .disabled(isScanning)
                    binding.btnSatelliteScan.setLoading(
                        loading = scanning,
                        loadingText = "Scanning…",
                        restingText = "Scan",
                    )
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

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
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
        val rb =
            inflateRow(
                binding.llSatelliteList,
                c.label,
                c.detail,
                statusText(c),
                kind = ConnectionKind.SATELLITE,
                state = c.live,
            )
        when (c.live) {
            LinkState.Connected, LinkState.Unstable -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Disconnect")
                rb.btnRowAction.setOnClickListener { satellite.disconnect(c.id) }
            }
            LinkState.Connecting -> {
                // In-button spinner accompanies the "Connecting…" label and
                // the button drops to 0.4 alpha while disabled. LinkState.Connecting
                // covers both pair phases (pairAndConnect → openSession) in the
                // SatelliteConnectionManager, so a single state flip drives the
                // whole in-flight visual — no separate pairingInFlight needed.
                rb.btnRowAction.setLoading(
                    loading = true,
                    loadingText = "Connecting…",
                    restingText = "Connect",
                )
                rb.btnRowAction.setOnClickListener(null)
            }
            // All resting non-live states share the same primary action today
            // (Connect attempts auto-pair → openSession). Splitting buttons by
            // Saved/Ready/Found/Stale is a UX call, not just a nomenclature
            // one — left intentionally collapsed here.
            LinkState.Saved, LinkState.Ready, LinkState.Found, LinkState.Stale -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Connect")
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
        // Unpaired-discovered (LinkState.Found) — these never sit in
        // ConnectionSummary because the hub only tracks remembered + live
        // entries; they're rendered directly from satellite.discoveredServers.
        val rb =
            inflateRow(
                binding.llSatelliteList,
                s.name.ifEmpty { s.ip },
                "${s.ip} • UDP ${s.udpPort}",
                "Found · ${getString(s.source.labelRes)}",
                kind = ConnectionKind.SATELLITE,
                state = LinkState.Found,
            )
        // Resting state by definition: as soon as the user taps Connect the
        // SatelliteConnectionManager calls markConnecting(), which lands the
        // row in `hub.connections` as LinkState.Connecting on the next emit,
        // and the row re-renders as a satelliteRow with the in-button spinner.
        // No need for a spinner here on the discovered-only branch.
        rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Connect")
        rb.btnRowAction.setOnClickListener { satellite.connect(s) }
        return rb.root
    }

    private fun btRow(c: ConnectionSummary): View {
        val rb =
            inflateRow(
                binding.llBtList,
                c.label,
                c.detail,
                statusText(c),
                kind = ConnectionKind.BLUETOOTH,
                state = c.live,
            )
        when (c.live) {
            LinkState.Connected, LinkState.Unstable -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Disconnect")
                rb.btnRowAction.setOnClickListener { btRegistry.stop(c.id) }
            }
            LinkState.Connecting -> {
                // Three sub-states inside Connecting, each with a label that
                // says what we're waiting on. The spinner accompanies the
                // label so it reads as one in-flight component — even for
                // "Pair from host", where we can't shorten the wait, the
                // spinner reassures the user that the surface is alive and
                // still listening for the host. All three are disabled with
                // 0.4 alpha per the design spec.
                val state = btRegistry.state(c.id)
                val label =
                    when {
                        state.registered -> "Pair from host"
                        state.acquiring -> "Acquiring…"
                        else -> "Waiting…"
                    }
                rb.btnRowAction.setLoading(
                    loading = true,
                    loadingText = label,
                    restingText = "Connect",
                )
                rb.btnRowAction.setOnClickListener(null)
            }
            LinkState.Saved, LinkState.Ready, LinkState.Found, LinkState.Stale -> {
                // "Connect" matches the satellite row's verb; reconnection here
                // still goes through tryAutoReconnect (BT can't be initiated
                // by us alone — the host has to look), but the user-facing
                // action word stays consistent with the rest of the page.
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Connect")
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

    // User-facing chip text per LinkState. The internal enum names (Live /
    // Linking / Faltering) live one layer down in [SessionState]; this layer's
    // job is to map every LinkState — including the discovery/pairing axis
    // values the wire layer doesn't know about — to a noun (resting) or
    // verb-with-ellipsis (transient) per the shared nomenclature.
    private fun statusText(c: ConnectionSummary): String =
        when (c.live) {
            LinkState.Found -> "Found"
            LinkState.Stale -> "Needs pairing"
            LinkState.Saved -> "Offline"
            LinkState.Ready -> "Ready"
            LinkState.Connecting -> "Connecting…"
            LinkState.Connected -> "Online"
            LinkState.Unstable -> "Unsteady"
        }

    private fun inflateRow(
        parent: ViewGroup,
        title: String,
        detail: String,
        status: String,
        kind: ConnectionKind,
        state: LinkState,
    ): RowConnectionBinding {
        val rb = RowConnectionBinding.inflate(layoutInflater, parent, false)
        rb.tvRowTitle.text = title
        rb.tvRowDetail.text = detail
        rb.tvRowStatus.text = status
        rb.dotRow.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        // Color map keyed on the user-facing chip text (the only thing the
        // inflater sees here). The "Unsteady" amber would ideally be a
        // distinct color but we share colorPrimary with "Connecting…" until
        // a real amber lands in colors.xml — both signal "transient, watch
        // this row" so the conflation is acceptable.
        val color =
            when (status) {
                "Online" -> R.color.colorSuccess
                "Connecting…", "Unsteady" -> R.color.colorPrimary
                else -> R.color.colorMuted
            }
        (rb.dotRow.background as GradientDrawable).setColor(getColor(color))
        rb.ivRowGlyph.setImageResource(rowGlyphRes(kind, state))
        return rb
    }

    /**
     * Pick the v6 brand glyph for a row based on the connection kind and its
     * current LinkState. The icon family lives in res/drawable/ic_{dish,
     * satellite,bluetooth}{,_connected,_off}.xml — same shapes shipped to the
     * other Dish clients and the satellite/web dashboard.
     *
     * Satellite rows use the satellite glyph (the row IS a satellite server
     * the phone is reaching out to). Bluetooth rows use the Berkana rune.
     * The same icon family carries through item_controller.xml so a slot
     * bound to one of these rows reads visually identically to its source.
     */
    private fun rowGlyphRes(
        kind: ConnectionKind,
        state: LinkState,
    ): Int =
        when (kind) {
            ConnectionKind.SATELLITE ->
                when (state) {
                    LinkState.Connected -> R.drawable.ic_satellite_connected
                    LinkState.Saved, LinkState.Stale -> R.drawable.ic_satellite_off
                    else -> R.drawable.ic_satellite
                }
            ConnectionKind.BLUETOOTH ->
                when (state) {
                    LinkState.Connected -> R.drawable.ic_bluetooth_connected
                    LinkState.Connecting -> R.drawable.ic_bluetooth_searching
                    LinkState.Saved, LinkState.Stale -> R.drawable.ic_bluetooth_off
                    else -> R.drawable.ic_bluetooth
                }
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
        // TODO(in-button loader inside the PIN dialog): the design spec
        // (dish-mac's PairingSheet.swift) keeps the Pair button visible with
        // an in-button DishSpinner while POST /api/pair is in flight, and
        // only dismisses on success. MaterialAlertDialogBuilder dismisses its
        // positive-button dialog on click — surfacing the in-flight state
        // inside the dialog would require swapping to a custom Dialog (or
        // overriding the alert's positive-button after show()). The in-row
        // loader on the satelliteRow already covers the openSession phase
        // (LinkState.Connecting fires from markConnecting() as soon as
        // pairWithPin starts), so the user sees a continuous in-flight
        // state in the row immediately after the dialog dismisses. Keeping
        // the simpler alert-builder pattern until UX confirms the dialog
        // needs to linger through the pair → connect round-trip.
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
    //  INPUT DISPATCH — forwarded to GamepadActivityHost
    // ═══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }
}
