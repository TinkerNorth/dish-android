// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
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
import androidx.core.net.toUri
import androidx.core.view.isNotEmpty
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
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.attachGamepadHost
import com.tinkernorth.dish.ui.common.dotColorForState
import com.tinkernorth.dish.ui.common.glyphForConnection
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.statusChipText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private var btAdapterBannerId: Long? = null
    private var btPermissionBannerId: Long? = null
    private var networkBannerId: Long? = null

    private val btStaleBannerIds = HashMap<String, Long>()

    private var pendingBtRegistration: PendingBtRegistration? = null

    private var discoverabilityExpiryJob: kotlinx.coroutines.Job? = null

    private var pinDialog: PairPinDialog? = null

    private var pairingServer: com.tinkernorth.dish.core.model.DiscoveredServer? = null

    private val btPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            // OS doesn't broadcast permission changes; ProcessLifecycleOwner.onStart re-poll never fires here.
            btPermissionState.refresh()
            if (results.values.all { it }) {
                showProfilePicker()
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
            btRegistry.start(pending.tempId, pending.profile)
            armDiscoverabilityExpiryTimer(pending.tempId)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost = attachGamepadHost(binding.root, wakeState, gamepadRegistry, notifications)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        bindSectionHeaders()

        observeSatelliteHub()
        observeSystemStateBanners()
        observeBluetoothRegistry()

        handlePairPromptIntent(intent)
    }

    private fun observeSatelliteHub() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connections.collect { conns ->
                    render(conns)
                    // Success path emits no ConnectionEvent, so observe state directly to dismiss PIN dialog.
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
                    binding.sectionSatellites.btnSectionAction.setLoading(
                        loading = scanning,
                        loadingText = getString(R.string.action_scanning),
                        restingText = getString(R.string.action_scan),
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
    }

    private fun observeSystemStateBanners() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btAdapterState.state.collect { state -> applyBtAdapterBanner(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btPermissionState.state.collect { state -> applyBtPermissionBanner(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkState.state.collect { state -> applyNetworkBanner(state) }
            }
        }
    }

    private fun observeBluetoothRegistry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.staleBtIds.collect { stale -> applyBtStaleBanners(stale) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.errors.collect { msg ->
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

    private fun bindSectionHeaders() {
        with(binding.sectionSatellites) {
            iconSection.visibility = View.VISIBLE
            iconSection.setImageResource(R.drawable.ic_satellite)
            labelSection.setText(R.string.section_satellites)
            btnSectionAction.visibility = View.VISIBLE
            btnSectionAction.setText(R.string.action_scan)
            btnSectionAction.setOnClickListener { satellite.startDiscovery() }
        }
        with(binding.sectionBluetooth) {
            iconSection.visibility = View.VISIBLE
            iconSection.setImageResource(R.drawable.ic_bluetooth)
            labelSection.setText(R.string.section_bluetooth_hosts)
            btnSectionAction.visibility = View.VISIBLE
            btnSectionAction.setText(R.string.action_add)
            btnSectionAction.setOnClickListener { requestBtPermissions() }
        }
    }

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

    private fun renderEmptyState() {
        val hasRows = binding.llSatelliteList.isNotEmpty()
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
                statusChipText(this, c.live),
                kind = ConnectionKind.SATELLITE,
                state = c.live,
            )
        when (c.live) {
            LinkState.Connected, LinkState.Unstable -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_disconnect))
                rb.btnRowAction.setOnClickListener { satellite.disconnect(c.id) }
            }
            LinkState.Connecting -> {
                rb.btnRowAction.setLoading(
                    loading = true,
                    loadingText = getString(R.string.chip_status_connecting),
                    restingText = getString(R.string.action_connect),
                )
                rb.btnRowAction.setOnClickListener(null)
            }
            LinkState.Stale -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_repair_short))
                rb.btnRowAction.setOnClickListener {
                    val remembered =
                        satellite.remembered().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    showPairingDialog(remembered.toDiscovered())
                }
            }
            LinkState.Saved, LinkState.Ready, LinkState.Found -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_connect))
                rb.btnRowAction.setOnClickListener {
                    val remembered = satellite.remembered().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    satellite.connect(remembered.toDiscovered())
                }
            }
        }
        rb.btnRowSecondary.visibility = View.VISIBLE
        rb.btnRowSecondary.text = getString(R.string.action_forget_short)
        rb.btnRowSecondary.setOnClickListener { satellite.forget(c.id) }
        return rb.root
    }

    private fun discoveredSatelliteRow(s: com.tinkernorth.dish.core.model.DiscoveredServer): View {
        val rb =
            inflateRow(
                binding.llSatelliteList,
                s.name.ifEmpty { s.ip },
                getString(R.string.discovered_row_detail, s.ip, s.udpPort),
                getString(R.string.discovered_row_status, getString(s.source.labelRes)),
                kind = ConnectionKind.SATELLITE,
                state = LinkState.Found,
            )
        rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_connect))
        rb.btnRowAction.setOnClickListener { satellite.connect(s) }
        return rb.root
    }

    private fun btRow(c: ConnectionSummary): View {
        val rb =
            inflateRow(
                binding.llBtList,
                c.label,
                c.detail,
                statusChipText(this, c.live),
                kind = ConnectionKind.BLUETOOTH,
                state = c.live,
            )
        when (c.live) {
            LinkState.Connected, LinkState.Unstable -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_disconnect))
                rb.btnRowAction.setOnClickListener { btRegistry.stop(c.id) }
            }
            LinkState.Connecting -> {
                val state = btRegistry.state(c.id)
                val label =
                    when {
                        state.registered -> getString(R.string.bt_row_pair_from_host)
                        state.acquiring -> getString(R.string.bt_row_acquiring)
                        else -> getString(R.string.bt_row_waiting)
                    }
                rb.btnRowAction.setLoading(
                    loading = true,
                    loadingText = label,
                    restingText = getString(R.string.action_connect),
                )
                rb.btnRowAction.setOnClickListener(null)
            }
            LinkState.Stale -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_repair_short))
                rb.btnRowAction.setOnClickListener {
                    val entry = store.rememberedBt().firstOrNull { it.id == c.id } ?: return@setOnClickListener
                    openBluetoothDeviceDetails(entry.mac)
                }
            }
            LinkState.Saved, LinkState.Ready, LinkState.Found -> {
                rb.btnRowAction.setLoading(loading = false, loadingText = "", restingText = getString(R.string.action_connect))
                rb.btnRowAction.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) btRegistry.tryAutoReconnect(c.id)
                }
            }
        }
        rb.btnRowSecondary.visibility = View.VISIBLE
        val rememberedEntry = store.rememberedBt().firstOrNull { it.id == c.id }
        val isRemembered = rememberedEntry != null
        rb.btnRowSecondary.text = getString(if (isRemembered) R.string.action_forget_short else R.string.action_cancel)
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
            .setTitle(getString(R.string.dialog_forget_bt_title, entry.name))
            .setMessage(getString(R.string.dialog_forget_bt_message))
            .setPositiveButton(R.string.dialog_forget_bt_positive) { _, _ ->
                commitForgetBt(id)
                openBluetoothDeviceDetails(entry.mac)
            }.setNegativeButton(R.string.dialog_forget_bt_negative) { _, _ ->
                commitForgetBt(id)
            }.setNeutralButton(R.string.action_cancel, null)
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
                data = "bt-mac:$mac".toUri()
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
        (rb.dotRow.background as GradientDrawable).setColor(getColor(dotColorForState(state)))
        rb.ivRowGlyph.setImageResource(glyphForConnection(kind, state))
        return rb
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            notifyBtUnsupported()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed =
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
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
            .setTitle(R.string.dialog_controller_profile_title)
            .setItems(names) { _, which -> startBtRegistration(profiles[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startBtRegistration(profile: BluetoothGamepad.GamepadProfile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val tempId = "bt-pending-${System.currentTimeMillis()}"
        // Defer HID registration until discoverability granted, else slot sits "Acquiring…" on deny.
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

    private fun showPairingDialog(server: com.tinkernorth.dish.core.model.DiscoveredServer) {
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
                        getString(R.string.pair_dialog_subtitle_named, server.name)
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

    private fun onConnectionError(message: String) {
        val dialog = pinDialog
        val pairing = pairingServer
        if (dialog != null && pairing != null) {
            dialog.setBusy(false)
            dialog.showError(message)
            return
        }
        notifications.error(
            glyph = R.drawable.ic_satellite_off,
            title = getString(R.string.notif_server_unreachable_title, pairing?.name ?: getString(R.string.satellite_fallback_name)),
            body = message,
        )
    }

    private fun notifyDiscoverabilityDenied() {
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

    private fun applyBtAdapterBanner(state: com.tinkernorth.dish.source.system.BluetoothAdapterState) {
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

    private fun applyBtStaleBanners(stale: Map<String, com.tinkernorth.dish.source.bluetooth.BtStaleReason>) {
        val gone = btStaleBannerIds.keys - stale.keys
        for (id in gone) {
            btStaleBannerIds.remove(id)?.let(notifications::dismiss)
        }
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

    private fun armDiscoverabilityExpiryTimer(connId: String) {
        discoverabilityExpiryJob?.cancel()
        discoverabilityExpiryJob =
            lifecycleScope.launch {
                kotlinx.coroutines.delay(DISCOVERABLE_SECONDS * 1000L)
                val state = btRegistry.state(connId)
                if (state.connected) return@launch
                notifications.warn(
                    glyph = R.drawable.ic_bluetooth_off,
                    title = getString(R.string.notif_discoverability_expired_title),
                    body = getString(R.string.notif_discoverability_expired_body),
                    action =
                        DishNotification.Action(
                            label = getString(R.string.action_re_extend),
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
        val resolvedProfile =
            btRegistry
                .state(connId)
                .profileName
                ?.let { name -> BluetoothGamepad.GamepadProfile.entries.firstOrNull { it.profileName == name } }
                ?: BluetoothGamepad.GamepadProfile.XBOX
        pendingBtRegistration = PendingBtRegistration(connId, resolvedProfile)
    }

    // SuppressLint: @RequiresPermission propagates from BluetoothAdapter field; gated by adapter-state banner and prior permission grant.
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestEnableBt() {
        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    companion object {
        const val EXTRA_PAIR_PROMPT_FOR_ID = "extra_pair_prompt_for_id"

        private const val DISCOVERABLE_SECONDS = 120
    }
}
