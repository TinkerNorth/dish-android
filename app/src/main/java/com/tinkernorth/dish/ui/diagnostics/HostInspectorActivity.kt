// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityHostInspectorBinding
import com.tinkernorth.dish.databinding.DiagnosticsCardActionsBinding
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HostInspectorActivity : BaseGamepadHostActivity() {
    private val viewModel: HostInspectorViewModel by viewModels()
    private lateinit var binding: ActivityHostInspectorBinding
    private val nav by lazy { DishNavigator(this) }

    override val holdsScreenAwake: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityHostInspectorBinding::inflate)
        setupDishToolbar(binding.toolbar)
        intent.getStringExtra(HostInspectorViewModel.EXTRA_LABEL)?.let { binding.toolbar.subtitle = it }

        binding.sectionLink.labelSection.setText(R.string.diagnostics_section_link)
        binding.sectionHostInfo.labelSection.setText(R.string.diagnostics_section_host_info)
        binding.sectionNetwork.labelSection.setText(R.string.diagnostics_section_network)
        binding.sectionBindings.labelSection.setText(R.string.diagnostics_section_bindings)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { render(it) }
            }
        }
    }

    private fun render(state: HostInspectorUiState) {
        val host = state.host
        if (host == null) {
            binding.containerLink.renderLines(emptyList(), getString(R.string.diagnostics_host_gone))
            binding.containerHost.renderLines(emptyList())
            binding.containerNetwork.renderLines(emptyList())
            binding.containerBindings.renderLines(emptyList())
            return
        }
        binding.toolbar.subtitle = host.label
        val sections = hostSections(host, state.nowMs)
        binding.containerLink.renderLines(sections.link)
        binding.containerHost.renderLines(sections.host, getString(R.string.diagnostics_unknown))
        renderNetwork(host, sections.network)
        renderBindings(host)
    }

    private fun renderNetwork(
        host: HostDiag,
        lines: List<String>,
    ) {
        val container = binding.containerNetwork
        container.renderLines(lines)
        val history =
            host.satellite
                ?.stats
                ?.rttRecentMs
                .orEmpty()
        if (history.isNotEmpty()) {
            val spark = layoutInflater.inflate(R.layout.diagnostics_rtt_sparkline, container, false) as SparklineView
            spark.update(history.toFloatArray())
            container.addView(spark)
        }
    }

    private fun renderBindings(host: HostDiag) {
        val container = binding.containerBindings
        container.removeAllViews()
        if (host.slots.isEmpty()) {
            container.addView(container.emptyRow(getString(R.string.diagnostics_no_bindings)))
            return
        }
        host.slots.forEach { slot ->
            container.addView(
                container.card(
                    slot.controllerName,
                    hostSlotLines(host.kind, slot, host.btProfile),
                ) { parent -> bindingButton(parent, slot) },
            )
        }
    }

    private fun bindingButton(
        parent: ViewGroup,
        slot: HostSlotDiag,
    ) = DiagnosticsCardActionsBinding
        .inflate(layoutInflater, parent, false)
        .apply {
            btnPrimary.setText(R.string.diagnostics_binding_button)
            btnPrimary.setOnClickListener { nav.toBindingInspector(slot.slotId, slot.controllerName) }
        }.root
}
