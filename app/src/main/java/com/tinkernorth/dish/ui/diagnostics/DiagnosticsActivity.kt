// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.databinding.ActivityDiagnosticsBinding
import com.tinkernorth.dish.databinding.DiagnosticsBodyRowBinding
import com.tinkernorth.dish.databinding.DiagnosticsCardBinding
import com.tinkernorth.dish.databinding.DiagnosticsEmptyRowBinding
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiBand
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.statusChipTextRes
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsViewModel.LatencyUi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@Suppress("TooManyFunctions")
@AndroidEntryPoint
class DiagnosticsActivity : BaseGamepadHostActivity() {
    @Inject lateinit var physicalInputNative: PhysicalInputNative

    @Inject lateinit var latencyProfilingStore: LatencyProfilingStore

    private val viewModel: DiagnosticsViewModel by viewModels()
    private lateinit var binding: ActivityDiagnosticsBinding
    private val nav by lazy { DishNavigator(this) }

    override val holdsScreenAwake: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityDiagnosticsBinding::inflate)
        setupDishToolbar(binding.toolbar)

        binding.sectionControllers.labelSection.setText(R.string.section_controllers)
        binding.sectionConnections.labelSection.setText(R.string.section_connections)
        binding.sectionLatency.labelSection.setText(R.string.diagnostics_section_latency)
        binding.sectionEvents.labelSection.setText(R.string.diagnostics_section_events)
        binding.btnCopyEvents.setOnClickListener { copyEventsToClipboard() }

        // Seed the switch synchronously BEFORE the listener exists: the collector below runs
        // async (onStart), so with profiling already on, its first emission would flip a live
        // listener and pop the confirmation on every screen open.
        binding.switchLatencyProfiling.isChecked = latencyProfilingStore.state.value

        observe(viewModel.controllers, ::renderControllers)
        observe(viewModel.hosts, ::renderHosts)
        observe(viewModel.wifi, ::renderWifi)
        observe(viewModel.latency, ::renderLatency)
        observe(viewModel.events, ::renderEvents)
        observe(latencyProfilingStore.state, ::syncLatencySwitch)
        wireLatencySwitch()
    }

    private fun <T> observe(
        flow: Flow<T>,
        render: (T) -> Unit,
    ) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { render(it) }
            }
        }
    }

    // ── Controllers ─────────────────────────────────────────────────────────

    private fun renderControllers(items: List<ControllerDiag>) {
        val container = binding.containerControllers
        container.removeAllViews()
        items.forEach { container.addView(controllerCard(container, it)) }
    }

    private fun controllerCard(
        parent: ViewGroup,
        diag: ControllerDiag,
    ): View {
        val lines = mutableListOf<String>()
        if (!diag.isVirtual) {
            lines += diagKv(R.string.diagnostics_transport, transportLabel(diag))
            lines += diagKv(R.string.diagnostics_poll_rate, hzLabel(diag.pollRateHz))
        }
        if (diag.hasGyro || diag.gyroHz > 0) {
            val gyro = if (diag.gyroHz > 0) hzLabel(diag.gyroHz) else getString(R.string.diagnostics_present)
            lines += diagKv(R.string.diagnostics_gyro, gyro)
        }
        diag.battery?.let { lines += diagKv(R.string.setup_cap_battery, batteryValue(it)) }
        if (!diag.isVirtual) lines += diagKv(R.string.diagnostics_state, controllerStateLabel(diag.state))
        lines += diagKv(R.string.diagnostics_host, hostValue(diag.host))
        diag.host?.let { lines += boundSlotLines(it) }
        if (diag.functions.isNotEmpty()) lines += diagKv(R.string.binding_label_functions, featureList(diag.functions))
        return cardWithTitle(parent, diag.name, lines) { footerParent -> inspectButton(footerParent, diag) }
    }

    private fun inspectButton(
        parent: ViewGroup,
        diag: ControllerDiag,
    ): View {
        val button = layoutInflater.inflate(R.layout.diagnostics_inspect_button, parent, false)
        button.setOnClickListener { nav.toInputInspector(diag.slotId, diag.name) }
        return button
    }

    // ── Hosts ───────────────────────────────────────────────────────────────

    private fun renderHosts(hosts: List<HostDiag>) {
        val container = binding.containerConnections
        container.removeAllViews()
        if (hosts.isEmpty()) {
            container.addView(emptyRow(container, getString(R.string.diagnostics_no_connections)))
            return
        }
        hosts.forEach { container.addView(hostCard(container, it)) }
    }

    private fun hostCard(
        parent: ViewGroup,
        host: HostDiag,
    ): View {
        val lines = mutableListOf<String>()
        lines += diagKv(R.string.diagnostics_transport, kindLabel(host.kind))
        lines += diagKv(R.string.diagnostics_link, getString(statusChipTextRes(host.live)))
        if (host.detail.isNotBlank()) lines += host.detail
        if (host.kind == ConnectionKind.SATELLITE) lines += satelliteLines(host)
        host.slots.forEach { lines += hostSlotLines(host.kind, it, host.btProfile) }
        return cardWithTitle(parent, host.label, lines)
    }

    private fun satelliteLines(host: HostDiag): List<String> {
        val lines = mutableListOf<String>()
        val telemetry = host.telemetry
        if (telemetry == null) {
            lines += diagKv(R.string.diagnostics_host, getString(R.string.diagnostics_offline))
        } else {
            val vigem = getString(if (telemetry.vigemAvailable) R.string.diagnostics_available else R.string.diagnostics_unavailable)
            lines += diagKv(R.string.diagnostics_vigem, vigem)
            lines += diagKv(R.string.diagnostics_active_controllers, telemetry.activeControllers.toString())
            lines += diagKv(R.string.diagnostics_server_epoch, telemetry.epoch.toString())
        }
        host.serverVersion?.let { lines += diagKv(R.string.diagnostics_host_version, it) }
        host.features?.let { features ->
            if (features.protocolVersion > 0) {
                lines +=
                    diagKv(R.string.diagnostics_host_protocol, getString(R.string.diagnostics_protocol_value, features.protocolVersion))
            }
            lines += diagKv(R.string.diagnostics_host_features, hostFeatureList(features))
        }
        return lines
    }

    // ── Latency profiling toggle + warning ──────────────────────────────────

    private fun syncLatencySwitch(enabled: Boolean) {
        if (binding.switchLatencyProfiling.isChecked != enabled) {
            binding.switchLatencyProfiling.isChecked = enabled
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

    private fun renderLatency(ui: LatencyUi) {
        val container = binding.containerLatencyStats
        container.removeAllViews()
        when (ui) {
            LatencyUi.Off -> container.addView(emptyRow(container, getString(R.string.diagnostics_latency_off_hint)))
            LatencyUi.Waiting -> container.addView(emptyRow(container, getString(R.string.diagnostics_latency_waiting)))
            is LatencyUi.Stats -> renderLatencyStats(container, ui.panel)
        }
    }

    private fun renderLatencyStats(
        container: ViewGroup,
        panel: LatencyPanel,
    ) {
        container.addView(statRow(container, getString(R.string.diagnostics_phone_path), panel.phonePathP50Ms, panel.phonePathP99Ms))
        container.addView(
            statRow(container, getString(R.string.diagnostics_polling_jitter), panel.pollingJitterP50Ms, panel.pollingJitterP99Ms),
        )
        container.addView(
            statRow(
                container,
                getString(R.string.diagnostics_network_latency),
                panel.networkOneWayP50Ms,
                null,
                approx = true,
                windowSamples = panel.rttSamples,
            ),
        )
        if (panel.rttHistoryMs.isNotEmpty()) {
            val spark = layoutInflater.inflate(R.layout.diagnostics_rtt_sparkline, container, false) as SparklineView
            spark.update(panel.rttHistoryMs.toFloatArray())
            container.addView(spark)
        }
    }

    private fun statRow(
        parent: ViewGroup,
        label: String,
        p50: Double?,
        p99: Double?,
        approx: Boolean = false,
        windowSamples: Int? = null,
    ): View {
        val value =
            when {
                p50 == null -> getString(R.string.diagnostics_unknown)
                approx && windowSamples != null ->
                    getString(R.string.diagnostics_ms_approx_window, p50, windowSamples)
                approx -> getString(R.string.diagnostics_ms_approx, p50)
                p99 == null -> getString(R.string.diagnostics_ms, p50)
                else -> getString(R.string.diagnostics_ms_p50_p99, p50, p99)
            }
        return bodyRow(parent, getString(R.string.diagnostics_kv, label, value))
    }

    // ── Events (flight recorder) ────────────────────────────────────────────

    private fun renderEvents(entries: List<DiagnosticsLogEntry>) {
        val container = binding.containerEvents
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(emptyRow(container, getString(R.string.diagnostics_events_empty)))
            return
        }
        entries
            .takeLast(SHOWN_EVENTS)
            .asReversed()
            .forEach { container.addView(bodyRow(container, formatEvent(it))) }
    }

    // Log lines are export material (English, fixed clock format), so bug reports paste uniformly.
    private fun formatEvent(entry: DiagnosticsLogEntry): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.atMs))
        return "$time [${entry.tag}] ${entry.message}"
    }

    private fun copyEventsToClipboard() {
        val text = viewModel.events.value.joinToString("\n") { formatEvent(it) }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_section_events), text))
    }

    // ── Wi-Fi link ──────────────────────────────────────────────────────────

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
        parent: ViewGroup,
        title: String,
        lines: List<String>,
        footer: ((ViewGroup) -> View)? = null,
    ): View {
        val card = DiagnosticsCardBinding.inflate(layoutInflater, parent, false)
        card.diagCardTitle.text = title
        lines.forEach { card.diagCardColumn.addView(bodyRow(card.diagCardColumn, it)) }
        footer?.let { card.diagCardColumn.addView(it(card.diagCardColumn)) }
        return card.root
    }

    private fun bodyRow(
        parent: ViewGroup,
        text: String,
    ): View = DiagnosticsBodyRowBinding.inflate(layoutInflater, parent, false).root.apply { this.text = text }

    private fun emptyRow(
        parent: ViewGroup,
        text: String,
    ): View = DiagnosticsEmptyRowBinding.inflate(layoutInflater, parent, false).root.apply { this.text = text }

    private companion object {
        const val SHOWN_EVENTS = 20
    }
}
