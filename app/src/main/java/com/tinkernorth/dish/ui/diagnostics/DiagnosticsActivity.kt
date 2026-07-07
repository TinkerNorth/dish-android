// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.databinding.ActivityDiagnosticsBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.DiagnosticsLogStore
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiBand
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.system.WifiLinkSource
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@Suppress("TooManyFunctions")
@AndroidEntryPoint
class DiagnosticsActivity : AppCompatActivity() {
    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var inputRateStore: InputRateStore

    @Inject lateinit var connectionCoordinator: ConnectionCoordinator

    @Inject lateinit var satelliteConnectionManager: SatelliteConnectionManager

    @Inject lateinit var controllerRepository: ControllerRepository

    @Inject lateinit var physicalInputNative: PhysicalInputNative

    @Inject lateinit var latencyProfilingStore: LatencyProfilingStore

    @Inject lateinit var capabilityComposer: CapabilityComposer

    @Inject lateinit var wifiLinkSource: WifiLinkSource

    @Inject lateinit var diagnosticsLog: DiagnosticsLogStore

    @Inject lateinit var json: Json

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()

        binding.sectionControllers.labelSection.setText(R.string.section_controllers)
        binding.sectionConnections.labelSection.setText(R.string.section_connections)
        binding.sectionLatency.labelSection.setText(R.string.diagnostics_section_latency)
        binding.sectionEvents.labelSection.setText(R.string.diagnostics_section_events)
        binding.btnCopyEvents.setOnClickListener { copyEventsToClipboard() }

        // Seed the switch synchronously BEFORE the listener exists: the collector below runs
        // async (onStart), so with profiling already on, its first emission would flip a live
        // listener and pop the confirmation on every screen open.
        binding.switchLatencyProfiling.isChecked = latencyProfilingStore.state.value

        observeControllers()
        observeConnections()
        observeLatencyToggle()
        wireLatencySwitch()
        observeLatencyStats()
        observeEvents()
        pollWifiLink()
    }

    // ── Controllers ─────────────────────────────────────────────────────────

    private fun observeControllers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gamepadRegistry.devices.collect { devices ->
                    renderControllers(devices.values.toList())
                }
            }
        }
    }

    private fun renderControllers(devices: List<PhysicalGamepadRegistry.Device>) {
        val container = binding.containerControllers
        container.removeAllViews()
        if (devices.isEmpty()) {
            container.addView(emptyRow(getString(R.string.diagnostics_no_controllers)))
            return
        }
        devices.forEach { container.addView(controllerCard(it)) }
    }

    private fun controllerCard(device: PhysicalGamepadRegistry.Device): android.view.View {
        val lines = mutableListOf<String>()
        lines += getString(R.string.diagnostics_kv, getString(R.string.diagnostics_transport), transportLabel(device))
        lines += getString(R.string.diagnostics_kv, getString(R.string.diagnostics_poll_rate), pollRateLabel(device))
        if (device.hasGyro) {
            lines += getString(R.string.diagnostics_kv, getString(R.string.diagnostics_gyro), gyroLabel(device))
        }
        lines += getString(R.string.diagnostics_kv, getString(R.string.diagnostics_state), controllerStateLabel(device))
        return cardWithTitle(device.name, lines) { parent -> inspectButton(parent, device) }
    }

    private fun inspectButton(
        parent: ViewGroup,
        device: PhysicalGamepadRegistry.Device,
    ): android.view.View {
        val button = layoutInflater.inflate(R.layout.diagnostics_inspect_button, parent, false)
        button.setOnClickListener {
            startActivity(
                android.content.Intent(this, InputInspectorActivity::class.java).apply {
                    putExtra(InputInspectorActivity.EXTRA_DEVICE_ID, device.id)
                    putExtra(InputInspectorActivity.EXTRA_DEVICE_NAME, device.name)
                },
            )
        }
        return button
    }

    private fun transportLabel(device: PhysicalGamepadRegistry.Device): String {
        val res =
            when {
                device.transport == Transport.Usb && device.isUsbSynthetic -> R.string.diagnostics_transport_usb_direct
                device.transport == Transport.Usb -> R.string.diagnostics_transport_usb_standard
                else -> R.string.diagnostics_transport_bluetooth
            }
        return getString(res)
    }

    private fun pollRateLabel(device: PhysicalGamepadRegistry.Device): String {
        val measured =
            inputRateStore.state.value.slots[device.id.toString()]
                ?.controllerHz
        val hz = if (measured != null && measured > 0) measured else device.pollRateHz
        return if (hz > 0) getString(R.string.diagnostics_hz, hz) else getString(R.string.diagnostics_unknown)
    }

    private fun gyroLabel(device: PhysicalGamepadRegistry.Device): String {
        val hz =
            inputRateStore.state.value.slots[device.id.toString()]
                ?.gyroHz ?: 0
        return if (hz > 0) getString(R.string.diagnostics_hz, hz) else getString(R.string.diagnostics_present)
    }

    private fun controllerStateLabel(device: PhysicalGamepadRegistry.Device): String {
        val res =
            when {
                device.needsReplug -> R.string.diagnostics_state_needs_replug
                device.restoreStuck -> R.string.diagnostics_state_transitioning
                device.transitioning -> R.string.diagnostics_state_transitioning
                device.isDisconnecting -> R.string.diagnostics_state_disconnecting
                else -> R.string.diagnostics_state_connected
            }
        return getString(res)
    }

    // ── Connections ─────────────────────────────────────────────────────────

    private fun observeConnections() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionCoordinator.connections.collect { renderConnections(it) }
            }
        }
    }

    private fun renderConnections(connections: List<ConnectionSummary>) {
        val container = binding.containerConnections
        container.removeAllViews()
        if (connections.isEmpty()) {
            container.addView(emptyRow(getString(R.string.diagnostics_no_connections)))
            return
        }
        connections.forEach { container.addView(connectionCard(it)) }
    }

    private fun connectionCard(summary: ConnectionSummary): android.view.View {
        val lines = mutableListOf<String>()
        lines += getString(R.string.diagnostics_kv, getString(R.string.diagnostics_link), summary.live.name)
        if (summary.kind == ConnectionKind.SATELLITE) {
            lines += satelliteTelemetry(summary.id)
        }
        return cardWithTitle(summary.label, lines)
    }

    private fun satelliteTelemetry(id: String): List<String> {
        val conn = satelliteConnectionManager.get(id)
        val handle = conn?.handle ?: HANDLE_NONE
        if (conn == null || handle == HANDLE_NONE) {
            return listOf(getString(R.string.diagnostics_kv, getString(R.string.diagnostics_host), getString(R.string.diagnostics_offline)))
        }
        val vigem = controllerRepository.getVigemAvailable(handle)
        val active = controllerRepository.getActiveControllerCount(handle)
        val epoch = controllerRepository.getServerEpoch(handle)
        return listOf(
            getString(R.string.diagnostics_kv, getString(R.string.diagnostics_vigem), vigemLabel(vigem)),
            getString(R.string.diagnostics_kv, getString(R.string.diagnostics_active_controllers), active.toString()),
            getString(R.string.diagnostics_kv, getString(R.string.diagnostics_server_epoch), epoch.toString()),
        ) + wireTruthLines(conn, controllerRepository.getActiveBitmap(handle))
    }

    // Declared vs applied per slot: what this client asked the satellite to plug against what
    // the satellite confirmed, so a converge failure is visible instead of inferred.
    private fun wireTruthLines(
        conn: com.tinkernorth.dish.source.connection.SatelliteConnection,
        activeBitmap: Int,
    ): List<String> =
        conn.slots.value.entries
            .sortedBy { it.value.controllerIndex }
            .flatMap { (slotId, binding) ->
                val typeRes =
                    if (binding.controllerType == CONTROLLER_TYPE_PLAYSTATION) {
                        R.string.picker_type_playstation
                    } else {
                        R.string.picker_type_xbox
                    }
                val mode = capabilityComposer.touchpadWireMode(slotId)
                val streaming = activeBitmap >= 0 && (activeBitmap and (1 shl binding.controllerIndex)) != 0
                listOf(
                    getString(R.string.diagnostics_wire_declared, binding.controllerIndex, getString(typeRes), mode),
                    getString(
                        R.string.diagnostics_wire_applied,
                        yesNo(binding.registered),
                        yesNo(streaming),
                    ),
                )
            }

    private fun yesNo(value: Boolean): String = getString(if (value) R.string.diagnostics_yes else R.string.diagnostics_no)

    private fun vigemLabel(value: Int): String =
        getString(if (value > 0) R.string.diagnostics_available else R.string.diagnostics_unavailable)

    // ── Latency profiling toggle + warning ──────────────────────────────────

    private fun observeLatencyToggle() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                latencyProfilingStore.state.collect { enabled ->
                    if (binding.switchLatencyProfiling.isChecked != enabled) {
                        binding.switchLatencyProfiling.isChecked = enabled
                    }
                }
            }
        }
    }

    private fun wireLatencySwitch() {
        binding.switchLatencyProfiling.setOnCheckedChangeListener { switch, isChecked ->
            // Programmatic syncs (collector echo, dialog-cancel revert) land here too; only a
            // change relative to the store is a user action worth confirming or persisting.
            if (isChecked == latencyProfilingStore.state.value) return@setOnCheckedChangeListener
            if (isChecked) {
                confirmEnableLatency(switch as MaterialSwitch)
            } else {
                disableLatency()
            }
        }
    }

    private fun confirmEnableLatency(switch: MaterialSwitch) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnostics_latency_warn_title)
            .setMessage(R.string.diagnostics_latency_warn_message)
            .setPositiveButton(R.string.diagnostics_latency_warn_positive) { _, _ -> enableLatency() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> revertSwitch(switch) }
            .setOnCancelListener { revertSwitch(switch) }
            .show()
    }

    private fun enableLatency() {
        latencyProfilingStore.setEnabled(true)
        physicalInputNative.setHotPathBench(true)
    }

    private fun disableLatency() {
        latencyProfilingStore.setEnabled(false)
        physicalInputNative.setHotPathBench(false)
    }

    // Revert the switch WITHOUT arming the native bench. The listener re-fires on this
    // programmatic flip, but the no-op guard sees isChecked == store (both false) and ignores it.
    private fun revertSwitch(switch: MaterialSwitch) {
        if (switch.isChecked) switch.isChecked = false
    }

    // ── Latency stats ───────────────────────────────────────────────────────

    private fun observeLatencyStats() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // collectLatest: pollLatencyStats() never returns, so a plain collect would
                // never see the off toggle and the stats would keep polling forever.
                latencyProfilingStore.state.collectLatest { enabled ->
                    if (enabled) pollLatencyStats() else renderLatencyOff()
                }
            }
        }
    }

    private fun renderLatencyOff() {
        val container = binding.containerLatencyStats
        container.removeAllViews()
        container.addView(emptyRow(getString(R.string.diagnostics_latency_off_hint)))
    }

    private suspend fun pollLatencyStats() {
        while (true) {
            renderLatencyStats(physicalInputNative.hotPathBenchJson(false))
            delay(LATENCY_POLL_MS)
        }
    }

    private fun renderLatencyStats(rawJson: String) {
        val container = binding.containerLatencyStats
        container.removeAllViews()
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
        if (root == null) {
            container.addView(emptyRow(getString(R.string.diagnostics_latency_waiting)))
            return
        }
        val phoneP50 = microToMs(root, STAGE1, P50)
        val phoneP99 = microToMs(root, STAGE1, P99)
        // The bench measures the full heartbeat round trip; one-way network latency is half
        // of it (symmetric-path estimate, hence the ~ rendering).
        val networkP50 = microToMs(root, RTT, P50)?.let { it / 2 }
        container.addView(statRow(getString(R.string.diagnostics_phone_path), phoneP50, phoneP99))
        container.addView(
            statRow(
                getString(R.string.diagnostics_polling_jitter),
                microToMs(root, URB_GAP, P50),
                microToMs(root, URB_GAP, P99),
            ),
        )
        container.addView(statRow(getString(R.string.diagnostics_network_latency), networkP50, null, approx = true))
        rttHistoryMs(root)?.let { history ->
            val spark = layoutInflater.inflate(R.layout.diagnostics_rtt_sparkline, container, false) as SparklineView
            spark.update(history)
            container.addView(spark)
        }
    }

    // Recent full-RTT samples in ms for the sparkline; null hides it until two samples exist.
    private fun rttHistoryMs(root: JsonObject): FloatArray? {
        val recent =
            runCatching {
                root[RTT_RECENT]?.jsonArray?.map { (it.jsonPrimitive.float / MICROS_PER_MS).toFloat() }
            }.getOrNull() ?: return null
        return if (recent.size < 2) null else recent.toFloatArray()
    }

    private fun microToMs(
        root: JsonObject,
        group: String,
        field: String,
    ): Double? {
        val value =
            runCatching {
                root[group]
                    ?.jsonObject
                    ?.get(field)
                    ?.jsonPrimitive
                    ?.float
            }.getOrNull() ?: return null
        return value / MICROS_PER_MS
    }

    private fun statRow(
        label: String,
        p50: Double?,
        p99: Double?,
        approx: Boolean = false,
    ): android.view.View {
        val value =
            when {
                p50 == null -> getString(R.string.diagnostics_unknown)
                approx -> getString(R.string.diagnostics_ms_approx, p50)
                p99 == null -> getString(R.string.diagnostics_ms, p50)
                else -> getString(R.string.diagnostics_ms_p50_p99, p50, p99)
            }
        return bodyRow(getString(R.string.diagnostics_kv, label, value))
    }

    // ── Events (flight recorder) ────────────────────────────────────────────

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                diagnosticsLog.state.collect { renderEvents(it) }
            }
        }
    }

    private fun renderEvents(entries: List<DiagnosticsLogEntry>) {
        val container = binding.containerEvents
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(emptyRow(getString(R.string.diagnostics_events_empty)))
            return
        }
        entries
            .takeLast(SHOWN_EVENTS)
            .asReversed()
            .forEach { container.addView(bodyRow(formatEvent(it))) }
    }

    // Log lines are export material (English, fixed clock format), so bug reports paste uniformly.
    private fun formatEvent(entry: DiagnosticsLogEntry): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.atMs))
        return "$time [${entry.tag}] ${entry.message}"
    }

    private fun copyEventsToClipboard() {
        val text = diagnosticsLog.state.value.joinToString("\n") { formatEvent(it) }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_section_events), text))
    }

    // ── Wi-Fi link ──────────────────────────────────────────────────────────

    private fun pollWifiLink() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderWifi(wifiLinkSource.read())
                    delay(WIFI_POLL_MS)
                }
            }
        }
    }

    private fun renderWifi(link: WifiLink?) {
        val value =
            when {
                link == null -> getString(R.string.diagnostics_wifi_none)
                else ->
                    getString(
                        R.string.diagnostics_wifi_value,
                        link.rssiDbm,
                        bandLabel(WifiBand.fromFrequencyMhz(link.frequencyMhz)),
                        link.linkSpeedMbps,
                    )
            }
        binding.tvWifiLink.text = getString(R.string.diagnostics_kv, getString(R.string.diagnostics_wifi), value)
    }

    private fun bandLabel(band: WifiBand): String =
        when (band) {
            // 2.4 GHz is the one worth calling out: it is the band that makes streaming laggy.
            WifiBand.GHZ_2_4 -> getString(R.string.diagnostics_wifi_band_warn)
            WifiBand.GHZ_5 -> "5 GHz"
            WifiBand.GHZ_6 -> "6 GHz"
            WifiBand.UNKNOWN -> getString(R.string.diagnostics_unknown)
        }

    // ── Row builders ────────────────────────────────────────────────────────

    private fun cardWithTitle(
        title: String,
        lines: List<String>,
        footer: ((ViewGroup) -> android.view.View)? = null,
    ): android.view.View {
        val card =
            com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.card_margin_bottom) }
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
                setPadding(pad, pad, pad, pad)
            }
        column.addView(titleView(title))
        lines.forEach { column.addView(bodyView(it)) }
        footer?.let { column.addView(it(column)) }
        card.addView(column)
        return card
    }

    private fun titleView(text: String): TextView =
        TextView(this, null, 0, R.style.TextAppearance_Dish_Title).apply {
            this.text = text
        }

    private fun bodyView(text: String): TextView =
        TextView(this, null, 0, R.style.TextAppearance_Dish_Body).apply {
            this.text = text
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.spacing_xs) }
        }

    private fun bodyRow(text: String): TextView =
        TextView(this, null, 0, R.style.TextAppearance_Dish_Body).apply {
            this.text = text
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.spacing_xs) }
        }

    private fun emptyRow(text: String): TextView =
        TextView(this, null, 0, R.style.TextAppearance_Dish_Body).apply {
            this.text = text
            gravity = Gravity.START
        }

    private companion object {
        const val HANDLE_NONE = -1
        const val LATENCY_POLL_MS = 2000L
        const val WIFI_POLL_MS = 2000L
        const val MICROS_PER_MS = 1000.0
        const val STAGE1 = "stage1_hotpath_us"
        const val URB_GAP = "urb_gap_us"
        const val RTT = "rtt_us"
        const val RTT_RECENT = "rtt_recent_us"
        const val P50 = "p50"
        const val P99 = "p99"
        const val SHOWN_EVENTS = 20
    }
}
