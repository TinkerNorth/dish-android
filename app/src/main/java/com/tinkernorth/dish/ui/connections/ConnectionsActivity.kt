// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.linkTierFor
import com.tinkernorth.dish.core.input.GamepadProfile
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityConnectionsBinding
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.bluetooth.BluetoothDeviceScanner
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BtStaleReason
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.generatePin
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionEvent
import com.tinkernorth.dish.source.notification.dishSnackbar
import com.tinkernorth.dish.source.store.BluetoothPermissionBannerStore
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver
import com.tinkernorth.dish.source.system.BluetoothPermissionBannerVariant
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import com.tinkernorth.dish.source.system.NetworkState
import com.tinkernorth.dish.source.system.NetworkStateObserver
import com.tinkernorth.dish.source.system.PERMISSION
import com.tinkernorth.dish.source.system.evaluate
import com.tinkernorth.dish.source.system.isGranted
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.StaticViewAdapter
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.setLoading
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.donate.attachDonatePill
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ConnectionsActivity : BaseGamepadHostActivity() {
    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var btScanner: BluetoothDeviceScanner

    @Inject lateinit var hub: ConnectionCoordinator

    @Inject lateinit var moonlight: com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager

    @Inject lateinit var store: ConnectionStore

    @Inject lateinit var btAdapterState: BluetoothAdapterStateObserver

    @Inject lateinit var btPermissionState: BluetoothPermissionStateObserver

    @Inject lateinit var btPermissionBannerStore: BluetoothPermissionBannerStore

    @Inject lateinit var networkState: NetworkStateObserver

    private lateinit var binding: ActivityConnectionsBinding
    private val viewModel: ConnectionsViewModel by viewModels()

    private lateinit var satelliteHeader: SectionHeaderAdapter
    private lateinit var bluetoothHeader: SectionHeaderAdapter
    private lateinit var moonlightHeader: SectionHeaderAdapter
    private lateinit var satelliteList: SatelliteListAdapter
    private lateinit var bluetoothList: BluetoothListAdapter
    private lateinit var moonlightList: MoonlightListAdapter

    private inner class SatelliteRows : SatelliteRowListener {
        override fun onConnect(row: SatelliteRow) {
            when (row) {
                is SatelliteRow.Known -> {
                    val remembered = satellite.remembered().firstOrNull { it.id == row.summary.id } ?: return
                    satellite.connect(remembered.toDiscovered())
                }
                is SatelliteRow.Discovered -> satellite.connect(row.server)
                is SatelliteRow.Empty -> Unit
            }
        }

        override fun onDisconnect(id: String) {
            satellite.disconnect(id)
        }

        override fun onRepair(id: String) {
            val remembered = satellite.remembered().firstOrNull { it.id == id } ?: return
            satellitePairing.show(remembered.toDiscovered())
        }

        override fun onForget(id: String) {
            hub.forgetConnection(id)
        }
    }

    private val satelliteRowListener = SatelliteRows()

    private inner class BluetoothRows : BluetoothRowListener {
        override fun onConnect(id: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) btRegistry.tryAutoReconnect(id)
        }

        override fun onDisconnect(id: String) {
            btRegistry.stop(id)
        }

        override fun onRepair(id: String) {
            val entry = store.rememberedBt().firstOrNull { it.id == id } ?: return
            openBluetoothDeviceDetails(entry.mac)
        }

        override fun onSecondary(summary: ConnectionSummary) {
            val remembered = store.rememberedBt().firstOrNull { it.id == summary.id }
            if (remembered != null) {
                confirmForgetBt(summary.id, remembered)
            } else {
                btRegistry.stop(summary.id)
            }
        }
    }

    private val bluetoothRowListener = BluetoothRows()

    private var btAdapterBannerId: Long? = null
    private var localNetworkBannerId: Long? = null

    private var localNetworkPrompted = false

    private val btPermissionBanner = BtPermissionBanner()

    private val connectionBanners = ConnectionBanners()

    private var pendingBtRegistration: PendingBtRegistration? = null

    private var addAfterPermission = false

    private var discoverabilityExpiryJob: kotlinx.coroutines.Job? = null

    // Set before launching the discoverability prompt so the result routes back to the active
    // caller (the new wait-for-host-first flow), bypassing the legacy pendingBtRegistration path.
    private var onDiscoverableResult: ((granted: Boolean, durationSec: Int) -> Unit)? = null

    private val satellitePairing = SatellitePairing()
    private val addHostDialogs = AddHostDialogs()

    // Nothing the user presses here may end in a shrug: a row whose button does nothing
    // is indistinguishable from a broken app, and used to be exactly that.
    private inner class MoonlightRows : MoonlightRowListener {
        override fun onPairKnown(summary: ConnectionSummary) {
            val host = hostFor(summary.id)
            if (host == null) {
                reportMoonlightHostGone(summary.label, summary.id)
                return
            }
            startMoonlightPairing(host)
        }

        override fun onPairDiscovered(host: com.tinkernorth.dish.core.net.moonlight.MoonlightHost) {
            startMoonlightPairing(host)
        }

        override fun onQuitSession(id: String) {
            val host = hostFor(id)
            if (host == null) {
                reportMoonlightHostGone(id, id)
                return
            }
            moonlight.quitHostApp(host)
        }

        override fun onForget(id: String) {
            confirmForgetMoonlight(id)
        }
    }

    private val moonlightRowListener = MoonlightRows()

    private var moonlightPinDialog: AlertDialog? = null

    // Held so Cancel actually cancels. Without it the dialog closed and phase 1 kept
    // its socket open for the whole two-minute PIN window.
    private var moonlightPairingJob: Job? = null

    private val btPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ ->
            btPermissionState.refresh()
            val continueToAdd = addAfterPermission
            addAfterPermission = false
            if (continueToAdd && !btPermissionState.state.value.connectMissing) {
                showProfilePicker()
            }
        }

    private val btDiscoverableLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val granted = result.resultCode != Activity.RESULT_CANCELED
            // RESULT_OK for this intent carries the granted duration in seconds; fall back to our request.
            val durationSec = if (result.resultCode > 0) result.resultCode else DISCOVERABLE_SECONDS
            val callback = onDiscoverableResult
            onDiscoverableResult = null
            if (callback != null) {
                callback(granted, durationSec)
                return@registerForActivityResult
            }
            val pending = pendingBtRegistration
            pendingBtRegistration = null
            if (!granted || pending == null) {
                notifyDiscoverabilityDenied()
                return@registerForActivityResult
            }
            btRegistry.start(pending.tempId, pending.profile, pending.autoConnectMac)
            armDiscoverabilityExpiryTimer(pending.tempId)
        }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                dismissLocalNetworkBanner()
                satellite.startDiscovery()
                moonlight.startDiscovery()
            } else {
                showLocalNetworkBanner()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installGamepadHost(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        attachDonatePill()
        setupList()

        observeSatelliteHub()
        observeSystemStateBanners()
        observeBluetoothRegistry()

        handlePairPromptIntent(intent)
    }

    private fun observeSatelliteHub() {
        observeUiState()
        observeSatelliteEvents()
        observeMoonlightEvents()
    }

    private fun observeUiState() {
        observeWhileStarted(viewModel.ui) { state -> renderConnections(state) }
    }

    private fun renderConnections(state: ConnectionsUiState) {
        render(state)
        binding.btnScanAll.setLoading(
            state.scanning || state.moonlightScanning,
            getString(R.string.action_scanning),
            getString(R.string.action_scan),
        )
        // The success path emits no ConnectionEvent, so the PIN dialog is dismissed off state.
        satellitePairing.dismissIfPaired(state)
    }

    private fun observeSatelliteEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                satellite.events.collect(::onSatelliteEvent)
            }
        }
    }

    private fun onSatelliteEvent(ev: ConnectionEvent) {
        when (ev) {
            is ConnectionEvent.Error -> onConnectionError(ev.message)
            is ConnectionEvent.PairingRequired -> satellitePairing.show(ev.server)
        }
    }

    private fun observeMoonlightEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                moonlight.events.collect(::onMoonlightEvent)
            }
        }
    }

    private fun onMoonlightEvent(ev: MoonlightConnectionEvent) {
        when (ev) {
            is MoonlightConnectionEvent.PairingPinReady -> showMoonlightPinDialog(ev.host, ev.pin)
            is MoonlightConnectionEvent.Paired -> onMoonlightPaired(ev)
            is MoonlightConnectionEvent.PairingFailed -> onMoonlightPairingFailed(ev)
            is MoonlightConnectionEvent.Notice -> onMoonlightNotice(ev)
            is MoonlightConnectionEvent.Error -> onMoonlightError(ev)
            // Every remaining event belongs to a session, and a session belongs to a binding; the
            // binding screen renders them where the user can act on them.
            else -> Unit
        }
    }

    // A pairing that succeeds has to LOOK like it succeeded. A host that already trusts this
    // device answers without a PIN, so there is no dialog to dismiss and the row's chip is the
    // only other feedback there would be.
    private fun onMoonlightPaired(ev: MoonlightConnectionEvent.Paired) {
        cancelMoonlightPairing()
        moonlightPinDialog?.dismiss()
        notifications.info(
            glyph = R.drawable.ic_pc_monitor,
            title = getString(R.string.ml_paired_title, ev.host.name),
            body = getString(R.string.ml_paired_body),
            key = "moonlight-paired",
        )
    }

    private fun onMoonlightPairingFailed(ev: MoonlightConnectionEvent.PairingFailed) {
        cancelMoonlightPairing()
        moonlightPinDialog?.dismiss()
        Log.w(TAG, "pairing with ${ev.host.address} failed: ${ev.reason}")
        notifications.error(
            glyph = R.drawable.ic_pc_monitor,
            title = getString(R.string.ml_pair_failed_title, ev.host.name),
            body = getString(R.string.ml_pair_failed_body),
        )
    }

    private fun onMoonlightNotice(ev: MoonlightConnectionEvent.Notice) {
        notifications.info(
            glyph = R.drawable.ic_pc_monitor,
            title = getString(R.string.section_moonlight_hosts),
            body = ev.message,
            key = "moonlight-notice",
        )
    }

    private fun onMoonlightError(ev: MoonlightConnectionEvent.Error) {
        moonlightPinDialog?.dismiss()
        notifications.error(
            glyph = R.drawable.ic_pc_monitor,
            title = getString(R.string.section_moonlight_hosts),
            body = ev.message,
        )
    }

    private fun observeSystemStateBanners() {
        observeWhileStarted(btAdapterState.state) { state -> applyBtAdapterBanner(state) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val bannerFlow =
                    combine(
                        btPermissionState.state,
                        btPermissionBannerStore.state,
                    ) { permission, dismissed ->
                        evaluate(permission, dismissed)
                    }
                bannerFlow.collect { variant -> btPermissionBanner.apply(variant) }
            }
        }
        observeWhileStarted(networkState.state) { state -> connectionBanners.applyNetwork(state) }
    }

    private fun observeBluetoothRegistry() {
        observeWhileStarted(btRegistry.staleBtIds) { stale -> connectionBanners.applyStaleBt(stale) }
        observeWhileStarted(btRegistry.errors) { msg ->
            notifications.error(
                title = "Bluetooth",
                body = msg,
                glyph = R.drawable.ic_bluetooth_off,
                action =
                    DishNotification.Action(
                        label = getString(R.string.action_retry),
                    ) { requestBtPermissions(continueToAdd = true) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairPromptIntent(intent)
    }

    private fun handlePairPromptIntent(intent: Intent) {
        val targetId = intent.getStringExtra(EXTRA_PAIR_PROMPT_FOR_ID) ?: return
        val remembered = satellite.remembered().firstOrNull { it.id == targetId } ?: return
        satellitePairing.show(remembered.toDiscovered())
        intent.removeExtra(EXTRA_PAIR_PROMPT_FOR_ID)
    }

    override fun onStart() {
        super.onStart()
        ensureLocalNetworkThenDiscover()
    }

    private fun setupList() {
        binding.btnScanAll.setOnClickListener { ensureLocalNetworkThenDiscover(userInitiated = true) }
        buildSectionHeaders()
        satelliteList = SatelliteListAdapter(satelliteRowListener)
        bluetoothList = BluetoothListAdapter(bluetoothRowListener)
        moonlightList = MoonlightListAdapter(moonlightRowListener)

        val single = binding.rvConnections
        if (single != null) {
            single.bindConnectionColumn(oneColumnAdapter())
            return
        }
        binding.rvSatellites?.bindConnectionColumn(ConcatAdapter(satelliteHeader, satelliteList))
        binding.rvBluetooth?.bindConnectionColumn(ConcatAdapter(bluetoothHeader, bluetoothList))
    }

    private fun buildSectionHeaders() {
        satelliteHeader =
            SectionHeaderAdapter(
                R.drawable.ic_satellite,
                R.string.section_satellites,
                R.string.action_add,
                tier = linkTierFor(ConnectionKind.SATELLITE),
            ) { addHostDialogs.showSatellite() }
        bluetoothHeader =
            SectionHeaderAdapter(
                R.drawable.ic_bluetooth,
                R.string.section_bluetooth_hosts,
                R.string.action_add,
                tier = linkTierFor(ConnectionKind.BLUETOOTH),
            ) { requestBtPermissions(continueToAdd = true) }
        moonlightHeader =
            SectionHeaderAdapter(
                R.drawable.ic_pc_monitor,
                R.string.section_moonlight_hosts,
                R.string.action_add,
                tier = linkTierFor(ConnectionKind.MOONLIGHT),
            ) { addHostDialogs.showMoonlight() }
    }

    // Narrow layouts stack every section in one scroller, dividers included.
    private fun oneColumnAdapter(): ConcatAdapter =
        ConcatAdapter(
            satelliteHeader,
            satelliteList,
            StaticViewAdapter(R.layout.item_connection_divider),
            moonlightHeader,
            moonlightList,
            StaticViewAdapter(R.layout.item_connection_divider),
            bluetoothHeader,
            bluetoothList,
        )

    private fun RecyclerView.bindConnectionColumn(concat: ConcatAdapter) {
        layoutManager = LinearLayoutManager(this@ConnectionsActivity)
        adapter = concat
        setHasFixedSize(true)
        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun render(state: ConnectionsUiState) {
        satelliteList.submitList(
            state.satelliteRows.ifEmpty { listOf(SatelliteRow.Empty(satelliteEmptyMessage(state.lastScanAtMs))) },
        )
        val rows =
            state.bluetoothSummaries.map { c ->
                BluetoothRow.Item(
                    BtRowUi(
                        summary = c,
                        connectingLabel = if (c.live == LinkState.Connecting) btConnectingLabel(c.id) else null,
                        secondaryIsForget = c.id in state.rememberedBtIds,
                    ),
                )
            }
        bluetoothList.submitList(rows.ifEmpty { listOf(BluetoothRow.Empty(getString(R.string.bt_hosts_empty))) })
        moonlightList.submitList(
            state.moonlightRows.ifEmpty { listOf(MoonlightRow.Empty(getString(R.string.moonlight_hosts_empty))) },
        )
    }

    private fun satelliteEmptyMessage(lastScanAtMs: Long?): String {
        val lastScan = lastScanAtMs ?: return getString(R.string.discovery_empty_never_scanned)
        return getString(R.string.discovery_empty_no_results, formatClock(lastScan))
    }

    private fun btConnectingLabel(id: String): String {
        val state = btRegistry.state(id)
        return when {
            state.registered -> getString(R.string.bt_row_pair_from_host)
            state.acquiring -> getString(R.string.bt_row_acquiring)
            else -> getString(R.string.bt_row_waiting)
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
        hub.forgetConnection(id)
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

    private fun requestBtPermissions(continueToAdd: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            notifyBtUnsupported()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed =
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                addAfterPermission = continueToAdd
                btPermissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        if (continueToAdd) showProfilePicker()
    }

    private fun showProfilePicker() {
        val profiles = GamepadProfile.entries
        val names = profiles.map { it.profileName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_controller_profile_title)
            .setItems(names) { _, which -> onBtProfileChosen(profiles[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onBtProfileChosen(profile: GamepadProfile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            startBtRegistration(profile)
            return
        }
        val tempId = "bt-pending-${System.currentTimeMillis()}"
        requestDiscoverable { granted, durationSec ->
            val discoverableUntilMs =
                if (granted) {
                    btRegistry.start(tempId, profile, autoConnectMac = null)
                    armDiscoverabilityExpiryTimer(tempId)
                    System.currentTimeMillis() + durationSec * 1000L
                } else {
                    null
                }
            showDevicePicker(profile, tempId, discoverableUntilMs)
        }
    }

    private fun requestDiscoverable(onResult: (granted: Boolean, durationSec: Int) -> Unit) {
        onDiscoverableResult = onResult
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            }
        btDiscoverableLauncher.launch(intent)
    }

    private fun showDevicePicker(
        profile: GamepadProfile,
        tempId: String,
        initialDiscoverableUntilMs: Long?,
    ) {
        DevicePickerSession(profile, tempId, initialDiscoverableUntilMs).show()
    }

    private inner class DevicePickerSession(
        private val profile: GamepadProfile,
        private val tempId: String,
        initialDiscoverableUntilMs: Long?,
    ) {
        // Inflated against a stand-in for the dialog's own FrameLayout so the root's layout
        // params resolve; attachToRoot=false keeps it detached until setView.
        private val view = layoutInflater.inflate(R.layout.dialog_bt_device_picker, FrameLayout(this@ConnectionsActivity), false)
        private val container = view.findViewById<LinearLayout>(R.id.deviceContainer)
        private val progress = view.findViewById<TextView>(R.id.scanProgress)
        private val empty = view.findViewById<TextView>(R.id.deviceEmpty)
        private val canScan = !btPermissionState.state.value.scanMissing
        private var discoverableUntilMs = initialDiscoverableUntilMs

        // Hosts already linked before this attempt; a newly connected id means our host arrived.
        private var baselineConnected = connectedIds()

        private val dialog =
            MaterialAlertDialogBuilder(this@ConnectionsActivity)
                .setTitle(R.string.dialog_bt_device_title)
                .setView(view)
                .setNeutralButton(R.string.bt_wait_for_host, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create()

        fun show() {
            view.findViewById<TextView>(R.id.scanNote).visibility =
                if (canScan) View.GONE else View.VISIBLE
            val job =
                lifecycleScope.launch {
                    launch { collectScan() }
                    launch { tickCountdown() }
                    launch { dismissOnConnect() }
                }
            dialog.setOnDismissListener { job.cancel() }
            dialog.setOnShowListener {
                refreshWaitButton()
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { onWaitForHostClicked() }
            }
            dialog.show()
        }

        private fun connectedIds(): Set<String> {
            val slots = btRegistry.states.value
            return slots.filterValues { it.connected }.keys
        }

        private fun remainingSeconds(): Int {
            val until = discoverableUntilMs ?: return 0
            val remainingMs = until - System.currentTimeMillis()
            return (remainingMs / 1000L).toInt().coerceAtLeast(0)
        }

        private fun refreshWaitButton() {
            val button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL) ?: return
            if (remainingSeconds() > 0) {
                button.isEnabled = false
                button.text = getString(R.string.bt_waiting_for_host_timer, remainingSeconds())
            } else {
                discoverableUntilMs = null
                button.isEnabled = true
                button.text = getString(R.string.bt_wait_for_host)
            }
        }

        private fun beginRegistration(
            autoConnectMac: String?,
            durationSec: Int,
        ) {
            discoverableUntilMs = System.currentTimeMillis() + durationSec * 1000L
            btRegistry.start(tempId, profile, autoConnectMac)
            armDiscoverabilityExpiryTimer(tempId)
            baselineConnected = connectedIds()
            refreshWaitButton()
        }

        private fun onWaitForHostClicked() {
            requestDiscoverable { granted, durationSec ->
                if (granted) beginRegistration(autoConnectMac = null, durationSec) else refreshWaitButton()
            }
        }

        private fun onDevicePicked(mac: String) {
            if (remainingSeconds() > 0) {
                btRegistry.start(tempId, profile, mac)
                dialog.dismiss()
                return
            }
            requestDiscoverable { granted, durationSec ->
                if (granted) {
                    beginRegistration(mac, durationSec)
                    dialog.dismiss()
                } else {
                    refreshWaitButton()
                }
            }
        }

        private suspend fun collectScan() {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btScanner.start(canScan)
                try {
                    btScanner.state.collect { renderScan(it) }
                } finally {
                    btScanner.stop()
                }
            }
        }

        private fun renderScan(state: BluetoothDeviceScanner.State) {
            progress.visibility = if (state.scanning) View.VISIBLE else View.GONE
            empty.visibility = if (state.devices.isEmpty() && !state.scanning) View.VISIBLE else View.GONE
            renderDeviceRows(container, state.devices) { onDevicePicked(it.mac) }
        }

        private suspend fun tickCountdown() {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshWaitButton()
                    delay(COUNTDOWN_TICK_MS)
                }
            }
        }

        private suspend fun dismissOnConnect() {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.states.collect { states ->
                    val connectedNow = states.filterValues { it.connected }.keys
                    if ((connectedNow - baselineConnected).isNotEmpty()) dialog.dismiss()
                }
            }
        }
    }

    private fun renderDeviceRows(
        container: LinearLayout,
        devices: List<BluetoothDeviceScanner.Device>,
        onPick: (BluetoothDeviceScanner.Device) -> Unit,
    ) {
        container.removeAllViews()
        for (device in devices) {
            val row = layoutInflater.inflate(R.layout.row_bt_device, container, false)
            row.findViewById<TextView>(R.id.tvDeviceName).text =
                device.name?.takeIf { it.isNotBlank() } ?: device.mac
            row.findViewById<TextView>(R.id.tvDeviceTag).setText(
                if (device.bonded) R.string.bt_device_tag_paired else R.string.bt_device_tag_available,
            )
            row.setOnClickListener { onPick(device) }
            container.addView(row)
        }
    }

    private fun startBtRegistration(
        profile: GamepadProfile,
        autoConnectMac: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val tempId = "bt-pending-${System.currentTimeMillis()}"
        // Defer HID registration until discoverability granted, else slot sits "Acquiring…" on deny.
        pendingBtRegistration = PendingBtRegistration(tempId, profile, autoConnectMac)
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            }
        btDiscoverableLauncher.launch(intent)
    }

    private data class PendingBtRegistration(
        val tempId: String,
        val profile: GamepadProfile,
        val autoConnectMac: String? = null,
    )

    // ── Moonlight host flow ─────────────────────────────────────────────────

    // The hosts screen owns trust and nothing else: pairing, forgetting, and the
    // escape hatch that closes an app the host is holding. The controller type,
    // the app, and the session itself belong to the binding.
    private fun startMoonlightPairing(host: com.tinkernorth.dish.core.net.moonlight.MoonlightHost) {
        moonlightPairingJob?.cancel()
        moonlightPairingJob = lifecycleScope.launch { moonlight.pairHost(host) }
    }

    private fun cancelMoonlightPairing() {
        moonlightPairingJob?.cancel()
        moonlightPairingJob = null
    }

    private fun reportMoonlightHostGone(
        label: String,
        id: String,
    ) {
        Log.w(TAG, "no Moonlight host behind $id; the row is stale")
        notifications.error(
            glyph = R.drawable.ic_pc_monitor,
            title = getString(R.string.ml_state_unreachable_title, label),
            body = getString(R.string.ml_host_gone_body),
        )
    }

    // Forget is UNILATERAL: the protocol has no unpair verb, so the host keeps its own
    // record of this device until a human removes it there. The confirmation says so,
    // mirroring the Bluetooth one, which has the same shape of half-truth to tell.
    private fun confirmForgetMoonlight(id: String) {
        val label = hub.summary(id)?.label ?: id
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_forget_moonlight_title, label))
            .setMessage(getString(R.string.dialog_forget_moonlight_message, label))
            .setPositiveButton(R.string.action_forget_short) { _, _ -> hub.forgetConnection(id) }
            .setNegativeButton(R.string.dialog_forget_bt_negative, null)
            .show()
    }

    private fun hostFor(id: String): com.tinkernorth.dish.core.net.moonlight.MoonlightHost? =
        moonlight.get(id)?.host?.value
            ?: moonlight.remembered.value
                .firstOrNull { it.id == id }
                ?.toHost()
            ?: moonlight.discovered.value.firstOrNull { it.id == id }

    private fun showMoonlightPinDialog(
        host: com.tinkernorth.dish.core.net.moonlight.MoonlightHost,
        pin: String,
    ) {
        moonlightPinDialog?.dismiss()
        val message = getString(R.string.ml_pair_pin_body, pin, host.name) + "\n\n" + getString(R.string.ml_pair_waiting)
        moonlightPinDialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.moonlight_pin_title, host.name))
                .setMessage(message)
                .setNegativeButton(R.string.action_cancel) { _, _ -> cancelMoonlightPairing() }
                .setOnDismissListener { moonlightPinDialog = null }
                .show()
    }

    // A pairing in flight owns the error: it belongs in the dialog the user is looking at, not in
    // a banner behind it.
    private fun onConnectionError(message: String) {
        if (satellitePairing.showError(message)) return
        notifications.error(
            glyph = R.drawable.ic_satellite_off,
            title =
                getString(
                    R.string.notif_server_unreachable_title,
                    satellitePairing.serverName ?: getString(R.string.satellite_fallback_name),
                ),
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
                ) { requestBtPermissions(continueToAdd = true) },
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

    private fun applyBtAdapterBanner(state: BluetoothAdapterState) {
        btAdapterBannerId?.let { notifications.dismiss(it) }
        btAdapterBannerId =
            when (state) {
                BluetoothAdapterState.ON -> null
                BluetoothAdapterState.UNSUPPORTED -> showBtUnsupportedBanner()
                BluetoothAdapterState.OFF -> showBtOffBanner()
            }
    }

    // A phone with no Bluetooth radio at all. Informational rather than a warning: there is
    // nothing for the user to fix, and the rest of the screen still works.
    private fun showBtUnsupportedBanner() =
        notifications.info(
            glyph = R.drawable.ic_bluetooth_off,
            title = getString(R.string.notif_bt_unsupported_title),
            body = getString(R.string.notif_bt_unsupported_body),
            key = "bt-adapter-unsupported",
            durationMs = DishNotification.DURATION_PERSISTENT,
        )

    // A radio that is off is one tap from working, so this one carries the action.
    private fun showBtOffBanner() =
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

    // The two dialogs that add a host by typing its address. Both keep themselves open on a
    // field the user still has to fix, which is the one thing they share and the reason they
    // sit together rather than on the Activity.
    private class AddSatelliteFields(
        view: View,
    ) {
        val hostLayout: TextInputLayout = view.findViewById(R.id.tilSatelliteHost)
        val httpsLayout: TextInputLayout = view.findViewById(R.id.tilSatelliteHttpsPort)
        val udpLayout: TextInputLayout = view.findViewById(R.id.tilSatelliteUdpPort)
        val hostField: TextInputEditText = view.findViewById(R.id.etSatelliteHost)
        val httpsField: TextInputEditText = view.findViewById(R.id.etSatelliteHttpsPort)
        val udpField: TextInputEditText = view.findViewById(R.id.etSatelliteUdpPort)
    }

    private class TypedSatellite(
        val host: String,
        val httpsPort: Int,
        val udpPort: Int,
    )

    private inner class AddHostDialogs {
        fun showSatellite() {
            val view = layoutInflater.inflate(R.layout.dialog_add_satellite, null)
            val fields = AddSatelliteFields(view)
            // Port fields parse back through toIntOrNull, so the defaults are written in ASCII digits.
            fields.httpsField.setText(String.format(Locale.ROOT, "%d", DEFAULT_HTTPS_PORT))
            fields.udpField.setText(String.format(Locale.ROOT, "%d", DEFAULT_UDP_PORT))

            val dialog =
                MaterialAlertDialogBuilder(this@ConnectionsActivity)
                    .setTitle(R.string.action_add_custom_satellite)
                    .setView(view)
                    .setPositiveButton(R.string.action_connect, null)
                    .setNegativeButton(R.string.action_cancel, null)
                    .create()
            // The positive button is wired after show() so a failed validation keeps the dialog open.
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (connectToTypedSatellite(fields)) dialog.dismiss()
                }
            }
            dialog.show()
        }

        /** Answers whether the typed host was accepted; paints the field errors when it was not. */

        // Null means the fields were marked with what is still missing. Every field is marked in the
        // same pass rather than stopping at the first, so one attempt shows everything to fix.
        private fun readTypedSatellite(fields: AddSatelliteFields): TypedSatellite? {
            val host =
                fields.hostField.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val httpsPort = parsePort(fields.httpsField)
            val udpPort = parsePort(fields.udpField)

            fields.hostLayout.error = if (host.isEmpty()) getString(R.string.add_satellite_error_host) else null
            fields.httpsLayout.error = if (httpsPort == null) getString(R.string.add_satellite_error_port) else null
            fields.udpLayout.error = if (udpPort == null) getString(R.string.add_satellite_error_port) else null

            if (host.isEmpty() || httpsPort == null || udpPort == null) return null
            return TypedSatellite(host, httpsPort, udpPort)
        }

        /** Answers whether the dialog may close. */
        private fun connectToTypedSatellite(fields: AddSatelliteFields): Boolean {
            val typed = readTypedSatellite(fields) ?: return false
            // A typed address has no mDNS name behind it, so it stands in for its own label until
            // the satellite answers with one.
            satellite.connect(
                DiscoveredServer(
                    name = typed.host,
                    ip = typed.host,
                    udpPort = typed.udpPort,
                    pairPort = typed.httpsPort,
                    httpPort = typed.httpsPort,
                    source = DiscoverySource.MANUAL,
                ),
            )
            return true
        }

        fun showMoonlight() {
            val view = layoutInflater.inflate(R.layout.dialog_add_moonlight, null)
            val layout = view.findViewById<TextInputLayout>(R.id.tilMoonlightHost)
            val input = view.findViewById<TextInputEditText>(R.id.etMoonlightHost)
            val dialog =
                MaterialAlertDialogBuilder(this@ConnectionsActivity)
                    .setTitle(R.string.action_add_moonlight_host)
                    .setView(view)
                    .setPositiveButton(R.string.action_add, null)
                    .setNegativeButton(R.string.action_cancel, null)
                    .create()
            // The positive button is wired after show() so a failed validation keeps the dialog open.
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (addTypedMoonlightHost(input, layout)) dialog.dismiss()
                }
            }
            dialog.show()
        }

        /** Answers whether the dialog may close: a blank address keeps it open, marked. */
        private fun addTypedMoonlightHost(
            input: TextInputEditText,
            layout: TextInputLayout,
        ): Boolean {
            val address =
                input.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (address.isEmpty()) {
                layout.error = getString(R.string.add_moonlight_error_host)
                return false
            }
            moonlight.addManualHost(address)
            return true
        }

        private fun parsePort(field: TextInputEditText): Int? {
            val port =
                field.text
                    ?.toString()
                    ?.trim()
                    ?.toIntOrNull() ?: return null
            return if (port in 1..MAX_PORT) port else null
        }
    }

    // The satellite PIN exchange: one dialog at a time and the server it belongs to, kept together
    // so the Activity does not carry either.
    private inner class SatellitePairing {
        private var dialog: PairPinDialog? = null
        private var server: DiscoveredServer? = null

        val serverName: String? get() = server?.name

        fun show(target: DiscoveredServer) {
            dialog?.dismiss()
            server = target
            // This dish's own PIN for the reverse direction: the operator can accept it on the
            // satellite instead of the user typing the server's PIN.
            val clientPin = generatePin()
            val built = build(target, clientPin)
            dialog = built
            built.show()
            // Sent immediately so the operator is notified the moment the user taps Connect, with
            // no extra "Accept on satellite" tap. The satellite-PIN field stays a fallback.
            built.setAwaitingApproval(true)
            satellite.requestApproval(target, clientPin)
        }

        /** Answers whether a pairing was in flight to take the error. */
        fun showError(message: String): Boolean {
            val live = dialog ?: return false
            if (server == null) return false
            live.setBusy(false)
            live.setAwaitingApproval(false)
            live.showError(message)
            return true
        }

        fun dismissIfPaired(state: ConnectionsUiState) {
            val pairing = server ?: return
            val pid = SatelliteConnection.idFor(pairing)
            val connected =
                state.satelliteRows.any {
                    it is SatelliteRow.Known && it.summary.id == pid && it.summary.live == LinkState.Connected
                }
            if (connected) dialog?.dismiss()
        }

        private fun build(
            target: DiscoveredServer,
            clientPin: String,
        ): PairPinDialog =
            PairPinDialog(
                this@ConnectionsActivity,
                clientPin = clientPin,
                onRequestApproval = { requestApprovalAgain(target, clientPin) },
            ) { pin -> submitServerPin(target, pin) }
                .apply {
                    dishTitle = getString(R.string.pair_dialog_title)
                    dishSubtitle = subtitleFor(target)
                    setOnDismissListener { forget(this) }
                }

        private fun requestApprovalAgain(
            target: DiscoveredServer,
            clientPin: String,
        ) {
            dialog?.setAwaitingApproval(true)
            dialog?.showError(null)
            satellite.requestApproval(target, clientPin)
        }

        private fun submitServerPin(
            target: DiscoveredServer,
            pin: String,
        ) {
            dialog?.setBusy(true)
            dialog?.showError(null)
            satellite.pairWithPin(target, pin)
        }

        private fun subtitleFor(target: DiscoveredServer): String {
            val isNamed = target.name.isNotEmpty()
            if (isNamed) return getString(R.string.pair_dialog_subtitle_named, target.name)
            return getString(R.string.pair_dialog_subtitle)
        }

        // Only the dialog still on screen may clear the fields; a late dismiss from a replaced one
        // must not wipe the new attempt.
        private fun forget(dismissed: PairPinDialog) {
            if (dialog !== dismissed) return
            dialog = null
            server = null
        }
    }

    // Two banner families with their own ids, kept together so the Activity does not carry the
    // dismissal bookkeeping for either.
    private inner class ConnectionBanners {
        private var networkBannerId: Long? = null
        private val staleBtBannerIds = HashMap<String, Long>()

        fun applyNetwork(state: NetworkState) {
            networkBannerId?.let { notifications.dismiss(it) }
            networkBannerId =
                when (state) {
                    NetworkState.WIFI -> null
                    NetworkState.NONE -> showNoNetwork()
                    NetworkState.CELLULAR -> showCellularOnly()
                }
        }

        fun applyStaleBt(stale: Map<String, BtStaleReason>) {
            val gone = staleBtBannerIds.keys - stale.keys
            for (id in gone) {
                staleBtBannerIds.remove(id)?.let(notifications::dismiss)
            }
            for ((id, reason) in stale) {
                val alreadyShowing = id in staleBtBannerIds
                if (alreadyShowing) continue
                val entry = store.rememberedBt().firstOrNull { it.id == id } ?: continue
                staleBtBannerIds[id] = showStaleBt(id, reason, entry)
            }
        }

        private fun openWifiSettingsAction() =
            DishNotification.Action(label = getString(R.string.action_open_settings)) { openWifiSettings() }

        private fun showNoNetwork(): Long =
            notifications.error(
                glyph = R.drawable.ic_satellite_off,
                title = getString(R.string.notif_no_network_title),
                body = getString(R.string.notif_no_network_body),
                action = openWifiSettingsAction(),
                key = "network-none",
                durationMs = DishNotification.DURATION_PERSISTENT,
            )

        private fun showCellularOnly(): Long =
            notifications.warn(
                glyph = R.drawable.ic_satellite_off,
                title = getString(R.string.notif_cellular_only_title),
                body = getString(R.string.notif_cellular_only_body),
                action = openWifiSettingsAction(),
                key = "network-cellular",
                durationMs = DishNotification.DURATION_PERSISTENT,
            )

        @StringRes
        private fun staleTitleRes(reason: BtStaleReason): Int =
            when (reason) {
                BtStaleReason.KEY_MISSING -> R.string.notif_bt_key_missing_title
                BtStaleReason.BOND_REMOVED -> R.string.notif_bt_bond_removed_title
            }

        @StringRes
        private fun staleBodyRes(reason: BtStaleReason): Int =
            when (reason) {
                BtStaleReason.KEY_MISSING -> R.string.notif_bt_key_missing_body
                BtStaleReason.BOND_REMOVED -> R.string.notif_bt_bond_removed_body
            }

        private fun showStaleBt(
            id: String,
            reason: BtStaleReason,
            entry: RememberedBt,
        ): Long =
            notifications.warn(
                glyph = R.drawable.ic_bluetooth_off,
                title = getString(staleTitleRes(reason), entry.name),
                body = getString(staleBodyRes(reason)),
                action =
                    DishNotification.Action(
                        label = getString(R.string.action_open_settings),
                    ) { openBluetoothDeviceDetails(entry.mac) },
                key = "bt-stale:$id",
                durationMs = DishNotification.DURATION_PERSISTENT,
            )
    }

    // One snackbar with its own state, kept together so ConnectionsActivity does not carry the
    // banner's fields and lifecycle alongside everything else.
    private inner class BtPermissionBanner {
        private var snackbar: Snackbar? = null
        private var shownVariant: BluetoothPermissionBannerVariant? = null

        private inner class DismissCallback : Snackbar.Callback() {
            override fun onDismissed(
                transientBottomBar: Snackbar?,
                event: Int,
            ) {
                val isTheOneShowing = snackbar === transientBottomBar
                if (isTheOneShowing) {
                    snackbar = null
                    shownVariant = null
                }
                val swipedAway = event == DISMISS_EVENT_SWIPE
                if (swipedAway) btPermissionBannerStore.markDismissed()
            }
        }

        fun apply(variant: BluetoothPermissionBannerVariant?) {
            if (variant == null) {
                hide()
                return
            }
            val alreadyShowingIt = variant == shownVariant && snackbar?.isShownOrQueued == true
            if (alreadyShowingIt) return
            snackbar?.dismiss()
            show(variant)
        }

        private fun hide() {
            shownVariant = null
            snackbar?.dismiss()
            snackbar = null
        }

        private fun show(variant: BluetoothPermissionBannerVariant) {
            val copy = copyFor(variant)
            val bar =
                dishSnackbar(
                    binding.root,
                    copy.severity,
                    getString(copy.titleRes),
                    getString(copy.bodyRes),
                    DishNotification.DURATION_PERSISTENT,
                )
            bar.setAction(getString(R.string.action_grant)) { requestBtPermissions(continueToAdd = false) }
            bar.addCallback(DismissCallback())
            shownVariant = variant
            snackbar = bar
            bar.show()
        }

        private fun copyFor(variant: BluetoothPermissionBannerVariant): BtPermissionBannerCopy =
            when (variant) {
                BluetoothPermissionBannerVariant.CONNECT ->
                    BtPermissionBannerCopy(
                        DishNotification.Severity.WARN,
                        R.string.notif_bt_permission_title,
                        R.string.notif_bt_permission_body,
                    )
                BluetoothPermissionBannerVariant.SCAN ->
                    BtPermissionBannerCopy(
                        DishNotification.Severity.INFO,
                        R.string.notif_bt_scan_permission_title,
                        R.string.notif_bt_scan_permission_body,
                    )
            }
    }

    private data class BtPermissionBannerCopy(
        val severity: DishNotification.Severity,
        val titleRes: Int,
        val bodyRes: Int,
    )

    private fun ensureLocalNetworkThenDiscover(userInitiated: Boolean = false) {
        if (isGranted(this)) {
            dismissLocalNetworkBanner()
            satellite.startDiscovery()
            moonlight.startDiscovery()
            return
        }
        if (userInitiated || !localNetworkPrompted) {
            localNetworkPrompted = true
            localNetworkPermissionLauncher.launch(PERMISSION)
        } else {
            showLocalNetworkBanner()
        }
    }

    private fun showLocalNetworkBanner() {
        if (localNetworkBannerId != null) return
        localNetworkBannerId =
            notifications.warn(
                glyph = R.drawable.ic_satellite_off,
                title = getString(R.string.notif_local_network_title),
                body = getString(R.string.notif_local_network_body),
                action =
                    DishNotification.Action(
                        label = getString(R.string.action_open_settings),
                    ) { openAppDetailsSettings() },
                key = "local-network-permission",
                durationMs = DishNotification.DURATION_PERSISTENT,
            )
    }

    private fun dismissLocalNetworkBanner() {
        localNetworkBannerId?.let { notifications.dismiss(it) }
        localNetworkBannerId = null
    }

    private fun openAppDetailsSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun armDiscoverabilityExpiryTimer(connId: String) {
        discoverabilityExpiryJob?.cancel()
        discoverabilityExpiryJob =
            lifecycleScope.launch {
                kotlinx.coroutines.delay(DISCOVERABLE_SECONDS * 1000L)
                // The slot re-keys from the temp id to bt:<mac> on connect, so check the live
                // session (single BT host at a time), not just connId.
                val slots = btRegistry.states.value.values
                if (slots.any { it.connected }) return@launch
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
                ?.let { name -> GamepadProfile.entries.firstOrNull { it.profileName == name } }
                ?: GamepadProfile.XBOX
        pendingBtRegistration = PendingBtRegistration(connId, resolvedProfile)
    }

    // The enable request needs BLUETOOTH_CONNECT from 31, and a cut-down build may carry no
    // activity for either intent. Both failures land on the same answer they always did: the
    // Bluetooth settings screen, which the user can act on without the grant.
    private fun requestEnableBt() {
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "enable request refused without BLUETOOTH_CONNECT: ${e.message}")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for the Bluetooth enable request: ${e.message}")
        }
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no Bluetooth settings screen on this device: ${e.message}")
        }
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    companion object {
        const val EXTRA_PAIR_PROMPT_FOR_ID = "extra_pair_prompt_for_id"

        private const val TAG = "ConnectionsActivity"
        private const val DISCOVERABLE_SECONDS = 120
        private const val COUNTDOWN_TICK_MS = 500L
        private const val DEFAULT_HTTPS_PORT = 9443
        private const val DEFAULT_UDP_PORT = 9876
        private const val MAX_PORT = 65535
    }
}
