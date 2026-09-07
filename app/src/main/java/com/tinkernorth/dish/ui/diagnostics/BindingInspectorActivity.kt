// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityBindingInspectorBinding
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BindingInspectorActivity : BaseGamepadHostActivity() {
    private val viewModel: BindingInspectorViewModel by viewModels()
    private lateinit var binding: ActivityBindingInspectorBinding

    override val holdsScreenAwake: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityBindingInspectorBinding::inflate)
        setupDishToolbar(binding.toolbar)
        intent.getStringExtra(BindingInspectorViewModel.EXTRA_LABEL)?.let { binding.toolbar.subtitle = it }

        binding.sectionBinding.labelSection.setText(R.string.diagnostics_section_binding)
        binding.sectionDeclared.labelSection.setText(R.string.diagnostics_section_declared)
        binding.sectionCapabilities.labelSection.setText(R.string.diagnostics_section_capabilities)
        binding.sectionStreams.labelSection.setText(R.string.diagnostics_section_streams)
        binding.sectionFeedback.labelSection.setText(R.string.diagnostics_section_feedback)
        binding.sectionLatency.labelSection.setText(R.string.diagnostics_section_latency)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { render(it) }
            }
        }
    }

    private fun render(state: BindingInspectorUiState) {
        val diag = state.binding
        binding.tvBindingEmpty.visibility = if (state.loaded && diag == null) View.VISIBLE else View.GONE
        binding.containerBound.visibility = if (diag == null) View.GONE else View.VISIBLE
        diag ?: return
        binding.toolbar.subtitle = diag.controllerName
        val sections = bindingSections(diag, state.nowMs)
        binding.containerBinding.renderLines(sections.binding)
        binding.containerDeclared.renderLines(sections.declared, getString(R.string.diagnostics_declared_none))
        binding.containerCapabilities.renderLines(sections.capabilities)
        binding.containerStreams.renderLines(sections.streams)
        binding.containerFeedback.renderLines(sections.feedback)
        binding.containerLatency.renderLines(sections.latency)
    }
}
