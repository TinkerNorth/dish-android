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
import com.tinkernorth.dish.databinding.DiagnosticsCardActionsBinding
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiBand
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.common.statusChipTextRes
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsViewModel.LatencyUi
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsViewModel.Overview
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
    private var latencyRows = LatencyRows(emptyList(), emptyList())
    private var latencyUi: LatencyUi = LatencyUi.Off

    override val holdsScreenAwake: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityDiagnosticsBinding::inflate)
        setupDishToolbar(binding.toolbar)

        binding.sectionRadios.labelSection.setText(R.string.diagnostics_section_radios)
        binding.sectionControllers.labelSection.setText(R.string.section_controllers)
        binding.sectionConnections.labelSection.setText(R.string.section_connections)
        binding.sectionLatency.labelSection.setText(R.string.diagnostics_section_latency)
        binding.sectionEvents.labelSection.setText(R.string.diagnostics_section_events)
        binding.btnCopyEvents.setOnClickListener { copyEventsToClipboard() }

        // Seed the switch synchronously BEFORE the listener exists: the collector below runs
        // async (onStart), so with profiling already on, its first emission would flip a live
        // listener and pop the confirmation on every screen open.
        binding.switchLatencyProfiling.isChecked = latencyProfilingStore.state.value

        observe(viewModel.overview, ::renderOverview)
        observe(viewModel.wifi, ::renderWifi)
        observe(viewModel.latency) { ui ->
            latencyUi = ui
            renderLatency()
        }
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

    private fun renderOverview(overview: Overview) {
        renderRadios(overview)
        renderControllers(overview.controllers)
        renderHosts(overview.hosts)
        latencyRows = overview.latencyRows
        renderLatency()
    }

    // ── Radios ──────────────────────────────────────────────────────────────

    private fun renderRadios(overview: Overview) {
        val container = binding.containerRadios
        container.removeAllViews()
        container.addView(container.card(getString(R.string.diagnostics_wifi), wifiLines(overview.radios)))
        container.addView(
            container.card(
                getString(R.string.overlay_connection_kind_bluetooth),
                bluetoothLines(overview.radios, overview.controllers, overview.hosts),
            ),
        )
        container.addView(container.card(getString(R.string.diagnostics_radio_usb), usbLines(overview.controllers)))
    }

    // ── Controllers ─────────────────────────────────────────────────────────

    private fun renderControllers(items: List<ControllerDiag>) {
        val container = binding.containerControllers
        container.removeAllViews()
        items.forEach { diag ->
            container.addView(container.card(diag.name, controllerLines(diag)) { parent -> controllerActions(parent, diag) })
        }
    }

    private fun controllerLines(diag: ControllerDiag): List<String> {
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
        return lines
    }

    private fun controllerActions(
        parent: ViewGroup,
        diag: ControllerDiag,
    ): View =
        DiagnosticsCardActionsBinding
            .inflate(layoutInflater, parent, false)
            .apply {
                btnPrimary.setText(R.string.diagnostics_inspect_button)
                btnPrimary.setOnClickListener { nav.toInputInspector(diag.slotId, diag.name) }
                btnSecondary.visibility = if (diag.host == null) View.GONE else View.VISIBLE
                btnSecondary.setText(R.string.diagnostics_binding_button)
                btnSecondary.setOnClickListener { nav.toBindingInspector(diag.slotId, diag.name) }
            }.root

    // ── Hosts ───────────────────────────────────────────────────────────────

    private fun renderHosts(hosts: List<HostDiag>) {
        val container = binding.containerConnections
        container.removeAllViews()
        if (hosts.isEmpty()) {
            container.addView(container.emptyRow(getString(R.string.diagnostics_no_connections)))
            return
        }
        hosts.forEach { host -> container.addView(container.card(host.label, hostLines(host)) { parent -> hostActions(parent, host) }) }
    }

    private fun hostLines(host: HostDiag): List<String> {
        val lines = mutableListOf<String>()
        lines += diagKv(R.string.diagnostics_transport, kindLabel(host.kind))
        lines += diagKv(R.string.diagnostics_link, getString(statusChipTextRes(host.live)))
        if (host.kind == ConnectionKind.SATELLITE) lines += satelliteHostLines(host)
        host.slots.forEach { lines += hostSlotLines(host.kind, it, host.btProfile) }
        return lines
    }

    private fun hostActions(
        parent: ViewGroup,
        host: HostDiag,
    ): View =
        DiagnosticsCardActionsBinding
            .inflate(layoutInflater, parent, false)
            .apply {
                btnPrimary.setText(R.string.diagnostics_host_details)
                btnPrimary.setOnClickListener { nav.toHostInspector(host.id, host.label) }
            }.root

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

    // ── Latency rows ────────────────────────────────────────────────────────

    private fun renderLatency() {
        val container = binding.containerLatencyStats
        container.removeAllViews()
        when (val ui = latencyUi) {
            LatencyUi.Off -> {
                container.addView(container.emptyRow(getString(R.string.diagnostics_latency_off_hint)))
                return
            }
            LatencyUi.Waiting -> container.addView(container.emptyRow(getString(R.string.diagnostics_latency_waiting)))
            is LatencyUi.Stats -> Unit
        }
        latencyRows.hosts.forEach { container.addView(container.bodyRow(diagKv(R.string.diagnostics_host, hostLatencyValue(it)))) }
        latencyRows.pads.forEach { row ->
            padTimingLines(row.facts).forEach { line ->
                container.addView(container.bodyRow(getString(R.string.diagnostics_joined, row.name, line)))
            }
        }
    }

    private fun hostLatencyValue(row: HostLatencyRow): String {
        val value =
            when {
                row.kind == ConnectionKind.MOONLIGHT ->
                    row.controlRttMs?.let { getString(R.string.diagnostics_ms_whole, it) } ?: getString(R.string.diagnostics_unknown)
                row.oneWayMs != null -> getString(R.string.diagnostics_ms_approx_window, row.oneWayMs, row.samples)
                else -> getString(R.string.diagnostics_unknown)
            }
        return getString(R.string.diagnostics_joined, row.label, value)
    }

    // ── Events (flight recorder) ────────────────────────────────────────────

    private fun renderEvents(entries: List<DiagnosticsLogEntry>) {
        val container = binding.containerEvents
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(container.emptyRow(getString(R.string.diagnostics_events_empty)))
            return
        }
        entries
            .takeLast(SHOWN_EVENTS)
            .asReversed()
            .forEach { container.addView(container.bodyRow(formatEvent(it))) }
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
                        wifiBandLabel(WifiBand.fromFrequencyMhz(link.frequencyMhz)),
                        link.linkSpeedMbps,
                    )
            }
        binding.tvWifiLink.text = getString(R.string.diagnostics_kv, getString(R.string.diagnostics_wifi), value)
    }

    private companion object {
        const val SHOWN_EVENTS = 20
    }
}
