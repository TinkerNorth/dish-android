// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ReportCategory
import com.tinkernorth.dish.databinding.ActivitySupportReportBinding
import com.tinkernorth.dish.databinding.DialogReportPreviewBinding
import com.tinkernorth.dish.databinding.ReportControllerChipBinding
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint

// Report a problem / Ask a question. The person's choices live in the views; the view model
// prepares the file from them on Preview and on Continue.
@AndroidEntryPoint
class SupportReportActivity : BaseGamepadHostActivity() {
    private val viewModel: SupportReportViewModel by viewModels()
    private lateinit var binding: ActivitySupportReportBinding

    // The controller chips the screen shows, by the slot each names.
    private var renderedSlots: List<String> = emptyList()
    private var defaultApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySupportReportBinding::inflate)
        setupDishToolbar(binding.toolbar)
        val start = viewModel.uiState.value.startCategory
        binding.toolbar.setTitle(if (start == ReportCategory.QUESTION) R.string.report_question else R.string.report_problem)
        binding.sectionCategory.labelSection.setText(R.string.report_category_heading)
        binding.sectionController.labelSection.setText(R.string.report_controller_heading)
        binding.sectionHolds.labelSection.setText(R.string.report_holds_heading)
        binding.tvReportHolds.text = holdsText()
        if (savedInstanceState == null) start?.let { binding.chipGroupCategory.check(chipFor(it)) }
        binding.btnReportPreview.setOnClickListener { viewModel.preview(draft()) }
        binding.btnReportContinue.setOnClickListener { viewModel.send(draft()) }
        observeWhileStarted(viewModel.uiState, ::render)
        observeWhileStarted(viewModel.events, ::onEvent)
    }

    private fun render(state: SupportReportViewModel.UiState) {
        renderControllers(state)
        binding.tvReportPreparing.visibility = if (state.preparing) View.VISIBLE else View.GONE
        binding.btnReportPreview.isEnabled = !state.preparing
        binding.btnReportContinue.isEnabled = !state.preparing
    }

    private fun renderControllers(state: SupportReportViewModel.UiState) {
        val slots = state.controllers.map { it.slotId }
        if (slots != renderedSlots) {
            val picked = pickedSlot()
            val group = binding.chipGroupControllers
            group.removeViews(1, group.childCount - 1)
            state.controllers.forEach { choice -> group.addView(controllerChip(choice)) }
            renderedSlots = slots
            picked?.let(::checkSlot)
        }
        val first = state.defaultSlotId
        if (!defaultApplied && first != null) {
            checkSlot(first)
            defaultApplied = true
        }
    }

    private fun controllerChip(choice: ReportControllerChoice): Chip {
        val chip = ReportControllerChipBinding.inflate(layoutInflater, binding.chipGroupControllers, false).root
        chip.id = View.generateViewId()
        chip.tag = choice.slotId
        chip.text = choice.name
        return chip
    }

    private fun checkSlot(slotId: String) {
        val chip = binding.chipGroupControllers.findViewWithTag<Chip>(slotId) ?: return
        binding.chipGroupControllers.check(chip.id)
    }

    private fun pickedSlot(): String? {
        val checked = binding.chipGroupControllers.checkedChipId
        return binding.chipGroupControllers.findViewById<Chip>(checked)?.tag as? String
    }

    private fun draft(): ReportDraft =
        ReportDraft(
            category = reportCategoryOrOther(pickedCategory()),
            text =
                binding.etReportText.text
                    ?.toString()
                    .orEmpty(),
            slotId = pickedSlot(),
        )

    private fun pickedCategory(): ReportCategory? {
        val checked = binding.chipGroupCategory.checkedChipId
        return categoryChips().firstOrNull { (chip, _) -> chip == checked }?.second
    }

    private fun chipFor(category: ReportCategory): Int = categoryChips().first { it.second == category }.first

    private fun categoryChips(): List<Pair<Int, ReportCategory>> =
        listOf(
            binding.chipNotRecognised.id to ReportCategory.NOT_RECOGNISED,
            binding.chipNoFeedback.id to ReportCategory.NO_FEEDBACK,
            binding.chipConnection.id to ReportCategory.CONNECTION,
            binding.chipAudio.id to ReportCategory.AUDIO,
            binding.chipOther.id to ReportCategory.OTHER,
            binding.chipQuestion.id to ReportCategory.QUESTION,
        )

    private fun holdsText(): String = HOLDS.joinToString("\n") { item -> getString(R.string.report_bullet, getString(item)) }

    private fun onEvent(event: SupportReportViewModel.Event) {
        when (event) {
            is SupportReportViewModel.Event.Preview -> showPreview(event.text)
            is SupportReportViewModel.Event.MailReady -> sendMail(event)
            SupportReportViewModel.Event.Failed -> Toast.makeText(this, R.string.diagnostics_email_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showPreview(text: String) {
        val preview = DialogReportPreviewBinding.inflate(layoutInflater)
        preview.tvReportPreview.text = text
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_preview)
            .setView(preview.root)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun sendMail(ready: SupportReportViewModel.Event.MailReady) {
        val report = ready.report
        val uri = FileProvider.getUriForFile(this, "$packageName$DIAGNOSTICS_AUTHORITY_SUFFIX", ready.file)
        val app = appFacts()
        val device = deviceFacts()
        val appLine = getString(R.string.diagnostics_summary_app, app.version, app.flavor, device.model, Build.VERSION.RELEASE.orEmpty())
        val body = reportMailBody(report.draft.text, mailSummary(report.overview, report.world, appLine))
        val subject = reportSubject(report.draft.category, app.version, device.model)
        val send = diagnosticsMailIntent(uri, subject, body)
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

    private companion object {
        const val TAG = "SupportReportActivity"

        // AndroidManifest.xml's FileProvider authority, after the application id.
        const val DIAGNOSTICS_AUTHORITY_SUFFIX = ".diagnostics"

        val HOLDS =
            listOf(
                R.string.report_holds_words,
                R.string.report_holds_phone,
                R.string.report_holds_controllers,
                R.string.report_holds_capture,
                R.string.report_holds_hosts,
                R.string.report_holds_log,
            )
    }
}
