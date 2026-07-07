// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

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
import com.tinkernorth.dish.source.store.LatencyProfilingStore
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@AndroidEntryPoint
class DiagnosticsActivity : AppCompatActivity() {
    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var inputRateStore: InputRateStore

    @Inject lateinit var connectionCoordinator: ConnectionCoordinator

    @Inject lateinit var satelliteConnectionManager: SatelliteConnectionManager

    @Inject lateinit var controllerRepository: ControllerRepository

    @Inject lateinit var physicalInputNative: PhysicalInputNative

    @Inject lateinit var latencyProfilingStore: LatencyProfilingStore

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

        observeControllers()
        observeConnections()
        observeLatencyToggle()
        wireLatencySwitch()
        observeLatencyStats()
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
        return cardWithTitle(device.name, lines)
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
        val handle = satelliteConnectionManager.get(id)?.handle ?: HANDLE_NONE
        if (handle == HANDLE_NONE) {
            return listOf(getString(R.string.diagnostics_kv, getString(R.string.diagnostics_host), getString(R.string.diagnostics_offline)))
        }
        val vigem = controllerRepository.getVigemAvailable(handle)
        val active = controllerRepository.getActiveControllerCount(handle)
        val epoch = controllerRepository.getServerEpoch(handle)
        return listOf(
            getString(R.string.diagnostics_kv, getString(R.string.diagnostics_vigem), vigemLabel(vigem)),
            getString(R.string.diagnostics_kv, getString(R.string.diagnostics_active_controllers), active.toString()),
            getString(R.string.diagnostics_kv, getString(R.string.diagnostics_server_epoch), epoch.toString()),
        )
    }

    private fun vigemLabel(value: Int): String =
        getString(if (value > 0) R.string.diagnostics_available else R.string.diagnostics_unavailable)

    // ── Latency profiling toggle + warning ──────────────────────────────────

    private fun observeLatencyToggle() {
        // Observe-then-bind: collecting before wiring the listener stops the first frame from
        // re-writing the persisted preference. Mirrors SettingsActivity's crash-reporting guard.
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
    // programmatic flip, but isChecked=false routes to disableLatency(), which only writes
    // the already-false preference and a redundant setHotPathBench(false): never arms.
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
        val rttP50 = microToMs(root, RTT, P50)
        container.addView(statRow(getString(R.string.diagnostics_phone_path), phoneP50, phoneP99))
        container.addView(statRow(getString(R.string.diagnostics_round_trip), rttP50, null))
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
    ): android.view.View {
        val value =
            when {
                p50 == null -> getString(R.string.diagnostics_unknown)
                p99 == null -> getString(R.string.diagnostics_ms, p50)
                else -> getString(R.string.diagnostics_ms_p50_p99, p50, p99)
            }
        return bodyRow(getString(R.string.diagnostics_kv, label, value))
    }

    // ── Row builders ────────────────────────────────────────────────────────

    private fun cardWithTitle(
        title: String,
        lines: List<String>,
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
        const val MICROS_PER_MS = 1000.0
        const val STAGE1 = "stage1_hotpath_us"
        const val RTT = "rtt_us"
        const val P50 = "p50"
        const val P99 = "p99"
    }
}
