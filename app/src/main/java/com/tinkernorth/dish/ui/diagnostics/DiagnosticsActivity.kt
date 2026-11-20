// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.databinding.ActivityDiagnosticsBinding
import com.tinkernorth.dish.databinding.DiagnosticsCardActionsBinding
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.system.wifiBandForFrequency
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.controllerDbCheckNowEnabled
import com.tinkernorth.dish.ui.common.controllerDbSourceLabel
import com.tinkernorth.dish.ui.common.controllerDbStatusResultLabel
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsViewModel.LatencyPanelUi
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsViewModel.LatencyUi
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsViewModel.Overview
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

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

        binding.sectionSupport.labelSection.setText(R.string.diagnostics_section_support)
        binding.sectionControllerDb.labelSection.setText(R.string.settings_section_controller_db)
        binding.btnEmailDiagnostics.setOnClickListener { onEmailTapped() }
        binding.btnControllerDbCheckNow.setOnClickListener { viewModel.checkControllerDbNow() }
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
        observe(viewModel.latencyPanel, ::renderLatency)
        observe(viewModel.events, ::renderEvents)
        observe(latencyProfilingStore.state, ::syncLatencySwitch)
        observe(viewModel.controllerDb, ::renderControllerDb)
        observe(viewModel.supportEvents, ::onSupportEvent)
        wireLatencySwitch()
    }

    private fun <T> observe(
        flow: Flow<T>,
        render: (T) -> Unit,
    ) {
        observeWhileStarted(flow) { render(it) }
    }

    private fun renderOverview(overview: Overview) {
        renderRadios(overview)
        renderControllers(overview.controllers, overview.ignoredInputs)
        renderHosts(overview.hosts)
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

    private fun renderControllers(
        items: List<ControllerDiag>,
        ignored: List<IgnoredInputDiag>,
    ) {
        val container = binding.containerControllers
        container.removeAllViews()
        items.forEach { diag ->
            container.addView(container.card(diag.name, controllerCardLines(diag)) { parent -> controllerActions(parent, diag) })
        }
        if (ignored.isEmpty()) return
        container.addView(container.emptyRow(getString(R.string.diagnostics_ignored_inputs_header)))
        ignored.forEach { device ->
            val title = device.name.ifBlank { getString(R.string.diagnostics_unknown) }
            container.addView(container.card(title, ignoredInputCardLines(device)))
        }
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
                btnTertiary.visibility = if (diag.isUsbSynthetic) View.VISIBLE else View.GONE
                btnTertiary.setText(R.string.diagnostics_capture_button)
                btnTertiary.setOnClickListener { onCaptureTapped(diag) }
            }.root

    private fun onCaptureTapped(diag: ControllerDiag) {
        Toast.makeText(this, getString(R.string.diagnostics_capture_started, diag.name), Toast.LENGTH_SHORT).show()
        viewModel.capture(diag)
    }

    // ── Controller database ─────────────────────────────────────────────────

    private fun renderControllerDb(status: ControllerDbStatus) {
        val container = binding.containerControllerDb
        container.removeAllViews()
        container.addView(container.card(getString(R.string.diagnostics_controller_db_title), controllerDbLines(status)))
        binding.btnControllerDbCheckNow.isEnabled = controllerDbCheckNowEnabled(status)
    }

    private fun controllerDbLines(status: ControllerDbStatus): List<String> {
        val active = getString(R.string.controller_db_version, status.activeVersion, getString(controllerDbSourceLabel(status.source)))
        val result = getString(controllerDbStatusResultLabel(status))
        val lastCheck =
            if (status.lastCheckAtMs > 0L) {
                getString(R.string.diagnostics_joined, result, agoLabel(System.currentTimeMillis(), status.lastCheckAtMs))
            } else {
                result
            }
        return listOf(
            diagKv(R.string.diagnostics_controller_db_bundled, status.bundledVersion.toString()),
            diagKv(R.string.diagnostics_controller_db_active, active),
            diagKv(R.string.diagnostics_controller_db_last_check, lastCheck),
        )
    }

    // ── Email diagnostics ───────────────────────────────────────────────────

    private fun onEmailTapped() {
        binding.btnEmailDiagnostics.isEnabled = false
        viewModel.emailDiagnostics()
    }

    private fun onSupportEvent(event: DiagnosticsViewModel.SupportEvent) {
        when (event) {
            is DiagnosticsViewModel.SupportEvent.MailReady -> sendMail(event.mail)
            DiagnosticsViewModel.SupportEvent.MailFailed -> mailFailed()
            is DiagnosticsViewModel.SupportEvent.Captured -> captured(event)
        }
    }

    private fun mailFailed() {
        binding.btnEmailDiagnostics.isEnabled = true
        Toast.makeText(this, R.string.diagnostics_email_failed, Toast.LENGTH_LONG).show()
    }

    private fun captured(event: DiagnosticsViewModel.SupportEvent.Captured) {
        val text = if (event.ok) R.string.diagnostics_capture_done else R.string.diagnostics_capture_failed
        Toast.makeText(this, getString(text, event.name), Toast.LENGTH_LONG).show()
    }

    private fun sendMail(mail: DiagnosticsViewModel.PreparedMail) {
        binding.btnEmailDiagnostics.isEnabled = true
        val uri = FileProvider.getUriForFile(this, "$packageName$DIAGNOSTICS_AUTHORITY_SUFFIX", mail.file)
        val app = appFacts()
        val device = deviceFacts()
        val appLine = getString(R.string.diagnostics_summary_app, app.version, app.flavor, device.model, Build.VERSION.RELEASE.orEmpty())
        val body = mailBody(mail.overview, mail.world, appLine)
        val send = diagnosticsMailIntent(uri, mailSubject(app.version, device.model), body)
        val mailPackages = packageManager.queryIntentActivities(mailAppQuery(), 0).map { it.activityInfo.packageName }.toSet()
        val sendHandlers = packageManager.queryIntentActivities(send, 0).map(::sendHandlerOf)
        val launch = mailLaunchFor(sendHandlers, mailPackages)
        try {
            startActivity(diagnosticsChooser(send, getString(R.string.diagnostics_email_chooser), launch))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app takes the diagnostics mail", e)
            Toast.makeText(this, R.string.diagnostics_email_no_app, Toast.LENGTH_LONG).show()
        }
    }

    // ── Hosts ───────────────────────────────────────────────────────────────

    private fun renderHosts(hosts: List<HostDiag>) {
        val container = binding.containerConnections
        container.removeAllViews()
        if (hosts.isEmpty()) {
            container.addView(container.emptyRow(getString(R.string.diagnostics_no_connections)))
            return
        }
        hosts.forEach { host -> container.addView(container.card(host.label, hostCardLines(host)) { parent -> hostActions(parent, host) }) }
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
        binding.switchLatencyProfiling.setOnCheckedChangeListener { switch, isChecked -> onLatencySwitchChanged(switch, isChecked) }
    }

    // Programmatic syncs (collector echo, dialog-cancel revert) land here too; only a change
    // relative to the store is a user action worth confirming or persisting.
    private fun onLatencySwitchChanged(
        switch: CompoundButton,
        checked: Boolean,
    ) {
        if (checked == latencyProfilingStore.state.value) return
        if (checked) {
            confirmEnableLatency(switch as MaterialSwitch)
        } else {
            disableLatency()
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

    private fun renderLatency(panel: LatencyPanelUi) {
        val container = binding.containerLatencyStats
        container.removeAllViews()
        when (panel.ui) {
            LatencyUi.Off -> {
                container.addView(container.emptyRow(getString(R.string.diagnostics_latency_off_hint)))
                return
            }
            LatencyUi.Waiting -> container.addView(container.emptyRow(getString(R.string.diagnostics_latency_waiting)))
            is LatencyUi.Stats -> Unit
        }
        panel.rows.hosts.forEach { container.addView(container.bodyRow(diagKv(R.string.diagnostics_host, hostLatencyValue(it)))) }
        panel.rows.pads.forEach { row ->
            padTimingLines(row.facts).forEach { line ->
                container.addView(container.bodyRow(getString(R.string.diagnostics_joined, row.name, line)))
            }
        }
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
                        wifiBandLabel(wifiBandForFrequency(link.frequencyMhz)),
                        link.linkSpeedMbps,
                    )
            }
        binding.tvWifiLink.text = getString(R.string.diagnostics_kv, getString(R.string.diagnostics_wifi), value)
    }

    private companion object {
        const val SHOWN_EVENTS = 20
        const val TAG = "DiagnosticsActivity"

        // AndroidManifest.xml's FileProvider authority, after the application id.
        const val DIAGNOSTICS_AUTHORITY_SUFFIX = ".diagnostics"
    }
}
