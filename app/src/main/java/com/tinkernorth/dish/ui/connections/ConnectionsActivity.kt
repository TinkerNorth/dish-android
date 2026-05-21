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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityConnectionsBinding
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BtStaleReason
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver
import com.tinkernorth.dish.source.system.BluetoothPermissionState
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import com.tinkernorth.dish.source.system.NetworkState
import com.tinkernorth.dish.source.system.NetworkStateObserver
import com.tinkernorth.dish.ui.common.dotColorForState
import com.tinkernorth.dish.ui.common.glyphForConnection
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.statusChipText
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
 *
 * Native [android.widget.Toast] is no longer used here; every feedback path
 * routes through [DishNotifications] and renders via the themed
 * [DishNotificationHost] overlay.
 */
@AndroidEntryPoint
class ConnectionsActivity : AppCompatActivity() {
    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var store: com.tinkernorth.dish.repository.ConnectionStore

    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    @Inject lateinit var btAdapterState: com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver

    @Inject lateinit var btPermissionState: com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver

    @Inject lateinit var networkState: com.tinkernorth.dish.source.system.NetworkStateObserver

    private lateinit var binding: ActivityConnectionsBinding
    private lateinit var gamepadHost: GamepadActivityHost

    // Stable ids for the system-state banners so each one replaces itself
    // rather than stacking, and we can dismiss them on state recovery.
    private var btAdapterBannerId: Long? = null
    private var btPermissionBannerId: Long? = null
    private var networkBannerId: Long? = null

    /** Per-host banner ids for KEY_MISSING / BOND_REMOVED, keyed by `bt:<MAC>`. */
    private val btStaleBannerIds = HashMap<String, Long>()

    /** Pending registration awaiting the discoverability-granted result. */
    private var pendingBtRegistration: PendingBtRegistration? = null

    /** Job that fires the "discoverability expired" notification. */
    private var discoverabilityExpiryJob: kotlinx.coroutines.Job? = null

    /** Live PIN dialog (if any) so we can drive its busy/error state from outside. */
    private var pinDialog: PairPinDialog? = null

    /** Server we're currently pairing with — drives the in-flight error wiring. */
    private var pairingServer: com.tinkernorth.dish.core.model.DiscoveredServer? = null

    private val btPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            if (results.values.all { it }) {
                showProfilePicker()
            } else {
                notifyBtPermissionDenied()
            }
        }

    private val btDiscoverableLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val pending = pendingBtRegistration
            pendingBtRegistration = null
            if (result.resultCode == Activity.RESULT_CANCELED || pending == null) {
                notifyDiscoverabilityDenied()
                return@registerForActivityResult
            }
            // Discoverability granted — NOW register the HID profile and arm
            // the expiration timer. The system gives us DISCOVERABLE_SECONDS
            // of host-visibility; if no host pairs in that window we surface
            // a "discoverability expired" banner with a re-extend action.
            btRegistry.start(pending.tempId, pending.profile)
            armDiscoverabilityExpiryTimer(pending.tempId)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost =
            GamepadActivityHost(this, binding.root, wakeState, gamepadRegistry)
                .also { it.install(notifications) }
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSatelliteScan.setOnClickListener { satellite.startDiscovery() }
        binding.btnBtAdd.setOnClickListener { requestBtPermissions() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect { conns ->
                    render(conns)
                    // Dismiss the PIN dialog the moment the pairing server's
                    // session flips to Connected — the success path doesn't
                    // emit a ConnectionEvent (no failure means no banner) so
                    // we observe the state directly.
                    val pairing = pairingServer
                    if (pairing != null) {
                        val pid = SatelliteConnection.idFor(pairing)
                        val summary = conns.firstOrNull { it.id == pid }
                        if (summary?.live == LinkState.Connected) {
                            pinDialog?.dismiss()
                        }
                    }
                }
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
                satellite.lastScanAtMs.collect { _ -> renderEmptyState() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect { ev ->
                    when (ev) {
                        is ConnectionEvent.Error -> onConnectionError(ev.message)
                        is ConnectionEvent.PairingRequired -> showPairingDialog(ev.server)
                    }
                }
            }
        }
        // Bluetooth adapter on/off. The banner is persistent until the user
        // toggles BT on, at which point we dismiss it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btAdapterState.state.collect { state -> applyBtAdapterBanner(state) }
            }
        }
        // BT runtime permission. Refreshed by the observer on every foreground
        // and after the permission launcher returns.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btPermissionState.state.collect { state -> applyBtPermissionBanner(state) }
            }
        }
        // Wi-Fi vs cellular vs none. Cellular-only is a guaranteed-failure
        // mode for satellite discovery — flag it before the user wastes a
        // scan and gets a no-results notification.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkState.state.collect { state -> applyNetworkBanner(state) }
            }
        }
        // KEY_MISSING / BOND_NONE on a remembered host. BluetoothBondMonitor
        // marks the registry's staleBtIds; we render the banner here so it
        // re-derives from live state on each activity bind instead of
        // ricocheting through the queue's replay buffer.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.staleBtIds.collect { stale -> applyBtStaleBanners(stale) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.errors.collect { msg ->
                    // No same-key — each BT error is distinct enough that the
                    // user benefits from seeing the stack rather than the
                    // newest silently replacing prior context.
                    notifications.error(
                        title = "Bluetooth",
                        body = msg,
                        glyph = R.drawable.ic_bluetooth_off,
                        action =
                            DishNotification.Action(
                                label = getString(R.string.action_retry),
                            ) { requestBtPermissions() },
                    )
                }
            }
        }

        // Deep-link from MainActivity when an auto-reconnect lands on Stale:
        // open the PIN dialog directly for that satellite so the user can
        // re-enter their PIN without scrolling to find the row.
        handlePairPromptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairPromptIntent(intent)
    }

    private fun handlePairPromptIntent(intent: Intent) {
        val targetId = intent.getStringExtra(EXTRA_PAIR_PROMPT_FOR_ID) ?: return
        val remembered = satellite.remembered().firstOrNull { it.id == targetId } ?: return
        showPairingDialog(remembered.toDiscovered())
        intent.removeExtra(EXTRA_PAIR_PROMPT_FOR_ID)
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
        renderEmptyState()

        val btConns = conns.filter { it.kind == ConnectionKind.BLUETOOTH }
        binding.llBtList.removeAllViews()
        btConns.forEach { binding.llBtList.addView(btRow(it)) }
        binding.tvBtEmpty.visibility =
            if (btConns.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Empty-state copy for the satellites section. Three states:
     *  - rows present → hidden.
     *  - never scanned → "Tap Scan…" (initial).
     *  - scanned, empty → "No satellites found at HH:MM — check…" with the
     *    last-scan timestamp so the message is honest about whether the
     *    feedback is current or stale.
     */
    private fun renderEmptyState() {
        val hasRows = binding.llSatelliteList.childCount > 0
        if (hasRows) {
            binding.tvSatelliteEmpty.visibility = View.GONE
            return
        }
        binding.tvSatelliteEmpty.visibility = View.VISIBLE
        val lastScan = satellite.lastScanAtMs.value
        binding.tvSatelliteEmpty.text =
            if (lastScan == null) {
                getString(R.string.discovery_empty_never_scanned)
            } else {
                val time = formatClock(lastScan)
                getString(R.string.discovery_empty_no_results, time)
            }
    }

    private fun formatClock(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        return String.format(
            java.util.Locale.ROOT,
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    private fun satelliteRow(c: ConnectionSummary): View {
        val rb =
            inflateRow(
                binding.llSatelliteList,
                c.label,
                c.detail,
                statusChipText(c.live),
                kind = ConnectionKind.SATELLITE,
                state = c.live,
            )
        when (c.live) {
            LinkState.Connected, LinkState.Unstable -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Disconnect")
                rb.btnRowAction.setOnClickListener { satellite.disconnect(c.id) }
            }
            LinkState.Connecting -> {
                rb.btnRowAction.setLoading(
                    loading = true,
                    loadingText = "Connecting…",
                    restingText = "Connect",
                )
                rb.btnRowAction.setOnClickListener(null)
            }
            LinkState.Stale -> {
                // The auto-reconnect path discovered the server has forgotten
                // us. Tapping the row offers a PIN entry directly rather than
                // an opaque "Connect" that would re-run the same dead pair.
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Re-pair")
                rb.btnRowAction.setOnClickListener {
                    val remembered =
                        satellite.remembered().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    showPairingDialog(remembered.toDiscovered())
                }
            }
            LinkState.Saved, LinkState.Ready, LinkState.Found -> {
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

    private fun discoveredSatelliteRow(s: com.tinkernorth.dish.core.model.DiscoveredServer): View {
        val rb =
            inflateRow(
                binding.llSatelliteList,
                s.name.ifEmpty { s.ip },
                "${s.ip} • UDP ${s.udpPort}",
                "Found · ${getString(s.source.labelRes)}",
                kind = ConnectionKind.SATELLITE,
                state = LinkState.Found,
            )
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
                statusChipText(c.live),
                kind = ConnectionKind.BLUETOOTH,
                state = c.live,
            )
        when (c.live) {
            LinkState.Connected, LinkState.Unstable -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Disconnect")
                rb.btnRowAction.setOnClickListener { btRegistry.stop(c.id) }
            }
            LinkState.Connecting -> {
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
            LinkState.Stale -> {
                // KEY_MISSING or BOND_NONE on this host. Action deep-links to
                // the OS device-details screen where the user can Forget on
                // the OS side and re-pair from there.
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Re-pair")
                rb.btnRowAction.setOnClickListener {
                    val entry = store.rememberedBt().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    openBluetoothDeviceDetails(entry.mac)
                }
            }
            LinkState.Saved, LinkState.Ready, LinkState.Found -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = "Connect")
                rb.btnRowAction.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) btRegistry.tryAutoReconnect(c.id)
                }
            }
        }
        rb.btnRowSecondary.visibility = View.VISIBLE
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
        (rb.dotRow.background as GradientDrawable).setColor(getColor(dotColorForState(state)))
        rb.ivRowGlyph.setImageResource(glyphForConnection(kind, state))
        return rb
    }

    // ── Bluetooth add flow ────────────────────────────────────────────────

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            notifyBtUnsupported()
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
        val tempId = "bt-pending-${System.currentTimeMillis()}"
        // Defer the HID registration until the user actually grants
        // discoverability — see btDiscoverableLauncher. If we registered
        // upfront and the user denied, the HID would sit "Acquiring…"
        // until they manually tapped Cancel. The pending id is stashed so
        // the deferred start can use it.
        pendingBtRegistration = PendingBtRegistration(tempId, profile)
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            }
        btDiscoverableLauncher.launch(intent)
    }

    private data class PendingBtRegistration(
        val tempId: String,
        val profile: BluetoothGamepad.GamepadProfile,
    )

    /**
     * Show the themed PIN dialog for [server]. The dialog stays up across
     * round-trips, surfacing an inline error from [onConnectionError] if the
     * server rejects the PIN — the old behaviour reset the user's typed PIN
     * on every failure.
     */
    private fun showPairingDialog(server: com.tinkernorth.dish.core.model.DiscoveredServer) {
        // Replace any prior dialog (e.g. user dismissed and re-tapped) to keep
        // there at most one in-flight pair operation.
        pinDialog?.dismiss()
        pairingServer = server
        val dialog =
            PairPinDialog(this) { pin ->
                pinDialog?.setBusy(true)
                pinDialog?.showError(null)
                satellite.pairWithPin(server, pin)
            }.apply {
                dishTitle = getString(R.string.pair_dialog_title)
                dishSubtitle =
                    if (server.name.isNotEmpty()) {
                        "Enter the PIN shown on ${server.name}."
                    } else {
                        getString(R.string.pair_dialog_subtitle)
                    }
                setOnDismissListener {
                    if (pinDialog === this) {
                        pinDialog = null
                        pairingServer = null
                    }
                }
            }
        pinDialog = dialog
        dialog.show()
    }

    /**
     * Funnel for [ConnectionEvent.Error]. When the PIN dialog is open and the
     * error is about pairing, surface it inline (keep the user's PIN typing);
     * otherwise post a themed banner.
     */
    private fun onConnectionError(message: String) {
        val dialog = pinDialog
        val pairing = pairingServer
        if (dialog != null && pairing != null) {
            // The dialog is busy from a pair submit — paper over the in-flight
            // state and surface the error inline.
            dialog.setBusy(false)
            dialog.showError(message)
            return
        }
        notifications.error(
            glyph = R.drawable.ic_satellite_off,
            title = getString(R.string.notif_server_unreachable_title, pairing?.name ?: "satellite"),
            body = message,
        )
    }

    private fun notifyBtPermissionDenied() {
        // Refresh the permission observer so the persistent banner from
        // applyBtPermissionBanner fires straight away (it normally only
        // refreshes on foreground entry).
        btPermissionState.refresh()
    }

    private fun notifyDiscoverabilityDenied() {
        // The HID registration was started before the discoverability dialog
        // was answered, so we have a dangling registration. Tear it down so
        // the row doesn't sit in "Acquiring…" forever, and tell the user.
        btRegistry.stopAll()
        notifications.warn(
            glyph = R.drawable.ic_bluetooth_off,
            title = getString(R.string.notif_bt_discoverability_denied_title),
            body = getString(R.string.notif_bt_discoverability_denied_body),
            action =
                DishNotification.Action(
                    label = getString(R.string.action_retry),
                ) { requestBtPermissions() },
            key = "bt-discoverability-denied",
        )
    }

    private fun notifyBtUnsupported() {
        notifications.info(
            glyph = R.drawable.ic_bluetooth_off,
            title = getString(R.string.notif_bt_unsupported_title),
            body = getString(R.string.notif_bt_unsupported_body),
            key = "bt-unsupported",
        )
    }

    // ── System-state banners ──────────────────────────────────────────────

    private fun applyBtAdapterBanner(state: com.tinkernorth.dish.source.system.BluetoothAdapterState) {
        // State-driven banner: persistent until the state recovers. Explicit
        // dismiss before each re-post so the dismissal animation fires when
        // the user toggles BT on, instead of relying on same-key replacement
        // which has a brief overlap.
        btAdapterBannerId?.let { notifications.dismiss(it) }
        btAdapterBannerId =
            when (state) {
                com.tinkernorth.dish.source.system.BluetoothAdapterState.ON -> null
                com.tinkernorth.dish.source.system.BluetoothAdapterState.UNSUPPORTED ->
                    notifications.info(
                        glyph = R.drawable.ic_bluetooth_off,
                        title = getString(R.string.notif_bt_unsupported_title),
                        body = getString(R.string.notif_bt_unsupported_body),
                        key = "bt-adapter-unsupported",
                        durationMs = DishNotification.DURATION_PERSISTENT,
                    )
                com.tinkernorth.dish.source.system.BluetoothAdapterState.OFF ->
                    notifications.warn(
                        glyph = R.drawable.ic_bluetooth_off,
                        title = getString(R.string.notif_bt_adapter_off_title),
                        body = getString(R.string.notif_bt_adapter_off_body),
                        action =
                            DishNotification.Action(
                                label = getString(R.string.action_turn_on),
                            ) { requestEnableBt() },
                        key = "bt-adapter-off",
                        durationMs = DishNotification.DURATION_PERSISTENT,
                    )
            }
    }

    private fun applyBtPermissionBanner(state: com.tinkernorth.dish.source.system.BluetoothPermissionState) {
        btPermissionBannerId?.let { notifications.dismiss(it) }
        btPermissionBannerId =
            if (state == com.tinkernorth.dish.source.system.BluetoothPermissionState.DENIED) {
                notifications.warn(
                    glyph = R.drawable.ic_bluetooth_off,
                    title = getString(R.string.notif_bt_permission_title),
                    body = getString(R.string.notif_bt_permission_body),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_grant),
                        ) { requestBtPermissions() },
                    key = "bt-permission-denied",
                    durationMs = DishNotification.DURATION_PERSISTENT,
                )
            } else {
                null
            }
    }

    /**
     * Reconcile the live KEY_MISSING / BOND_REMOVED set against the
     * Snackbar ids we have on screen. New entries get a persistent banner
     * with a deep-link action; entries that disappeared (because the user
     * re-paired and Connected restored) get dismissed.
     */
    private fun applyBtStaleBanners(stale: Map<String, com.tinkernorth.dish.source.bluetooth.BtStaleReason>) {
        // Dismiss banners whose host is no longer stale.
        val gone = btStaleBannerIds.keys - stale.keys
        for (id in gone) {
            btStaleBannerIds.remove(id)?.let(notifications::dismiss)
        }
        // Post banners for newly-stale hosts.
        for ((id, reason) in stale) {
            if (id in btStaleBannerIds) continue
            val entry = store.rememberedBt().firstOrNull { it.id == id } ?: continue
            val titleRes =
                when (reason) {
                    com.tinkernorth.dish.source.bluetooth.BtStaleReason.KEY_MISSING ->
                        R.string.notif_bt_key_missing_title
                    com.tinkernorth.dish.source.bluetooth.BtStaleReason.BOND_REMOVED ->
                        R.string.notif_bt_bond_removed_title
                }
            val bodyRes =
                when (reason) {
                    com.tinkernorth.dish.source.bluetooth.BtStaleReason.KEY_MISSING ->
                        R.string.notif_bt_key_missing_body
                    com.tinkernorth.dish.source.bluetooth.BtStaleReason.BOND_REMOVED ->
                        R.string.notif_bt_bond_removed_body
                }
            btStaleBannerIds[id] =
                notifications.warn(
                    glyph = R.drawable.ic_bluetooth_off,
                    title = getString(titleRes, entry.name),
                    body = getString(bodyRes),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_open_settings),
                        ) { openBluetoothDeviceDetails(entry.mac) },
                    key = "bt-stale:$id",
                    durationMs = DishNotification.DURATION_PERSISTENT,
                )
        }
    }

    private fun applyNetworkBanner(state: com.tinkernorth.dish.source.system.NetworkState) {
        networkBannerId?.let { notifications.dismiss(it) }
        networkBannerId =
            when (state) {
                com.tinkernorth.dish.source.system.NetworkState.WIFI -> null
                com.tinkernorth.dish.source.system.NetworkState.NONE ->
                    notifications.error(
                        glyph = R.drawable.ic_satellite_off,
                        title = getString(R.string.notif_no_network_title),
                        body = getString(R.string.notif_no_network_body),
                        action =
                            DishNotification.Action(
                                label = getString(R.string.action_open_settings),
                            ) { openWifiSettings() },
                        key = "network-none",
                        durationMs = DishNotification.DURATION_PERSISTENT,
                    )
                com.tinkernorth.dish.source.system.NetworkState.CELLULAR ->
                    notifications.warn(
                        glyph = R.drawable.ic_satellite_off,
                        title = getString(R.string.notif_cellular_only_title),
                        body = getString(R.string.notif_cellular_only_body),
                        action =
                            DishNotification.Action(
                                label = getString(R.string.action_open_settings),
                            ) { openWifiSettings() },
                        key = "network-cellular",
                        durationMs = DishNotification.DURATION_PERSISTENT,
                    )
            }
    }

    /**
     * Schedule a one-shot timer that fires the moment the OS-granted
     * discoverability window expires. If the slot is still in a not-yet-
     * Connected state by then, the host never paired; surface that with an
     * actionable banner so the user can re-extend without remembering the
     * 120-second number themselves. Cancels itself on activity stop +
     * on successful pair (checked when the timer fires).
     */
    private fun armDiscoverabilityExpiryTimer(connId: String) {
        discoverabilityExpiryJob?.cancel()
        discoverabilityExpiryJob =
            lifecycleScope.launch {
                kotlinx.coroutines.delay(DISCOVERABLE_SECONDS * 1000L)
                val state = btRegistry.state(connId)
                if (state.connected) return@launch
                notifications.warn(
                    glyph = R.drawable.ic_bluetooth_off,
                    title = "Discoverability expired",
                    body = "No host paired in time. Re-extend to keep trying.",
                    action =
                        DishNotification.Action(
                            label = "Re-extend",
                        ) {
                            relaunchDiscoverabilityFor(connId)
                        },
                    key = "bt-discoverability-expired:$connId",
                )
            }
    }

    private fun relaunchDiscoverabilityFor(connId: String) {
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            }
        btDiscoverableLauncher.launch(intent)
        // Preserve the profile so the deferred btRegistry.start() in the
        // result-callback hands the host the same controller signature it
        // had before the expiry.
        val resolvedProfile =
            btRegistry
                .state(connId)
                .profileName
                ?.let { name -> BluetoothGamepad.GamepadProfile.entries.firstOrNull { it.profileName == name } }
                ?: BluetoothGamepad.GamepadProfile.XBOX
        pendingBtRegistration = PendingBtRegistration(connId, resolvedProfile)
    }

    /**
     * Fire `ACTION_REQUEST_ENABLE` to ask the user to turn Bluetooth on.
     * The intent itself doesn't require permission to launch, but Android
     * lint reports the BluetoothAdapter constant access requires
     * BLUETOOTH_CONNECT on API 31+ — `@RequiresPermission` propagates from
     * the field. We already gate the banner on the adapter being available
     * + ask the user for permission before this path is reachable; the
     * lint suppression captures that this is the intended user-driven flow.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestEnableBt() {
        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
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

    companion object {
        /**
         * Intent extra: when present, opens the PIN dialog directly for the
         * given satellite id. Used by MainActivity's Pairing-Needed banner
         * "OPEN" action so the user lands one tap from re-entering their PIN.
         */
        const val EXTRA_PAIR_PROMPT_FOR_ID = "extra_pair_prompt_for_id"

        /**
         * How long the OS makes us discoverable for new BT hosts. Mirrors the
         * value passed in [BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION], and
         * drives the matching timer that surfaces an expired-discoverability
         * banner if no host pairs in this window.
         */
        private const val DISCOVERABLE_SECONDS = 120
    }
}
