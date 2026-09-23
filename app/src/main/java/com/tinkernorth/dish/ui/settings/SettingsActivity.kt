// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.settings

import android.os.Bundle
import android.text.TextUtils
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySettingsBinding
import com.tinkernorth.dish.source.store.CrashReportingStore
import com.tinkernorth.dish.source.store.ThemeMode
import com.tinkernorth.dish.source.store.ThemePreferenceStore
import com.tinkernorth.dish.source.update.UpdateNoticePhase
import com.tinkernorth.dish.source.update.UpdateNotices
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.donate.attachDonatePill
import com.tinkernorth.dish.ui.donate.bindDonateSettingsCard
import com.tinkernorth.dish.ui.update.updateStatusLine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : BaseGamepadHostActivity() {
    @Inject lateinit var crashReportingStore: CrashReportingStore

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    @Inject lateinit var updateNotices: UpdateNotices

    private lateinit var binding: ActivitySettingsBinding
    private val nav by lazy { DishNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySettingsBinding::inflate)
        setupDishToolbar(binding.toolbar)
        attachDonatePill()

        bindSectionLabels()
        bindSetupSection()
        bindAppearanceSection()
        bindDiagnosticsSection()
        bindAboutSection()
        bindDonateSettingsCard()
        bindCrashReportingSwitch()
        bindUpdateSection()
        binding.tvVersion.text = formatVersion()
    }

    private fun bindSectionLabels() {
        binding.sectionSetup.labelSection.setText(R.string.settings_section_setup)
        binding.sectionAppearance.labelSection.setText(R.string.settings_section_appearance)
        binding.sectionDiagnostics.labelSection.setText(R.string.settings_section_diagnostics)
        binding.sectionAbout.labelSection.setText(R.string.settings_section_about)
    }

    private fun bindSetupSection() {
        binding.cardRowSetupWizard.cardRowIcon.setImageResource(R.drawable.ic_route)
        binding.cardRowSetupWizard.cardRowTitle.setText(R.string.settings_setup_wizard_title)
        binding.cardRowSetupWizard.cardRowSubtitle.setText(R.string.settings_setup_wizard_body)
        binding.cardSetupWizard.setOnClickListener { nav.toSetupInput() }

        binding.cardRowHelp.cardRowIcon.setImageResource(R.drawable.ic_help)
        binding.cardRowHelp.cardRowTitle.setText(R.string.settings_help_title)
        binding.cardRowHelp.cardRowSubtitle.setText(R.string.settings_help_body)
        binding.cardHelp.setOnClickListener { nav.toHelp() }
    }

    private fun bindAppearanceSection() {
        binding.cardRowAppearance.cardRowIcon.setImageResource(R.drawable.ic_contrast)
        binding.cardRowAppearance.cardRowTitle.setText(R.string.settings_appearance_title)
        binding.cardRowAppearance.cardRowSubtitle.setText(R.string.settings_appearance_body)
        chooseChip(themePreferenceStore.state.value)
        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds -> onThemeChipsChanged(checkedIds) }
    }

    private fun onThemeChipsChanged(checkedIds: List<Int>) {
        // selectionRequired guarantees one id, but a configuration-change rebind can briefly
        // observe an empty selection. Guard rather than crash.
        val mode = themeModeForChip(checkedIds.firstOrNull()) ?: return
        val alreadyChosen = mode == themePreferenceStore.state.value
        if (alreadyChosen) return
        themePreferenceStore.setMode(mode)
    }

    private fun themeModeForChip(chipId: Int?): ThemeMode? =
        when (chipId) {
            R.id.chipThemeLight -> ThemeMode.LIGHT
            R.id.chipThemeDark -> ThemeMode.DARK
            R.id.chipThemeSystem -> ThemeMode.SYSTEM
            else -> null
        }

    private fun bindDiagnosticsSection() {
        binding.cardRowDiagnostics.cardRowIcon.setImageResource(R.drawable.ic_bug)
        binding.cardRowDiagnostics.cardRowTitle.setText(R.string.settings_diagnostics_title)
        binding.cardRowDiagnostics.cardRowSubtitle.setText(R.string.settings_diagnostics_body)
        binding.cardDiagnostics.setOnClickListener { nav.toDiagnostics() }

        binding.cardRowCrash.cardRowIcon.setImageResource(R.drawable.ic_bug)
        binding.cardRowCrash.cardRowTitle.setText(R.string.settings_crash_reporting_title)
        binding.cardRowCrash.cardRowSubtitle.setText(R.string.settings_crash_reporting_body)
    }

    private fun bindAboutSection() {
        binding.cardRowPrivacy.cardRowIcon.setImageResource(R.drawable.ic_shield)
        binding.cardRowPrivacy.cardRowTitle.setText(R.string.menu_privacy_policy)
        binding.cardRowPrivacy.cardRowSubtitle.apply {
            setTextAppearance(R.style.TextAppearance_Dish_Body_Mono)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        binding.cardRowPrivacy.cardRowSubtitle.text = privacyPolicyHost()
        binding.cardPrivacyPolicy.setOnClickListener { openExternalUrl(getString(R.string.url_privacy_policy)) }

        binding.cardRowOpenSourceLicenses.cardRowIcon.setImageResource(R.drawable.ic_license)
        binding.cardRowOpenSourceLicenses.cardRowTitle.setText(R.string.settings_open_source_licenses_title)
        binding.cardRowOpenSourceLicenses.cardRowSubtitle.setText(R.string.settings_open_source_licenses_body)
        binding.cardOpenSourceLicenses.setOnClickListener { nav.toLicenses() }
    }

    private fun privacyPolicyHost(): String =
        getString(R.string.url_privacy_policy)
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")

    private fun bindCrashReportingSwitch() {
        // Observe-then-bind: opposite order would re-write the persisted preference on the first frame.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                crashReportingStore.state.collect { enabled -> showCrashReportingEnabled(enabled) }
            }
        }
        binding.switchCrashReporting.setOnCheckedChangeListener { _, isChecked ->
            crashReportingStore.setEnabled(isChecked)
        }
    }

    private fun showCrashReportingEnabled(enabled: Boolean) {
        val alreadyShowing = binding.switchCrashReporting.isChecked == enabled
        if (alreadyShowing) return
        binding.switchCrashReporting.isChecked = enabled
    }

    // GitHub build only: the switch mirrors the store the same observe-then-bind
    // way as crash reporting, and the second row is a button whose text follows
    // the coordinator's status (check now, or open the download on offer).
    private fun bindUpdateSection() {
        binding.groupUpdates.isVisible = updateNotices.supported
        if (!updateNotices.supported) return
        binding.sectionUpdates.labelSection.setText(R.string.settings_section_updates)
        binding.cardRowUpdateChecks.cardRowIcon.setImageResource(R.drawable.ic_refresh)
        binding.cardRowUpdateChecks.cardRowTitle.setText(R.string.settings_update_checks_title)
        binding.cardRowUpdateChecks.cardRowSubtitle.setText(R.string.settings_update_checks_body)
        binding.cardRowUpdateCheckNow.cardRowIcon.setImageResource(R.drawable.ic_arrow_upward)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateNotices.status.collect { status ->
                    if (binding.switchUpdateChecks.isChecked != status.checksEnabled) {
                        binding.switchUpdateChecks.isChecked = status.checksEnabled
                    }
                    val line = updateStatusLine(status)
                    binding.cardRowUpdateCheckNow.cardRowTitle.setText(line.title)
                    binding.cardRowUpdateCheckNow.cardRowSubtitle.text =
                        line.versionArg?.let { getString(line.body, it) } ?: getString(line.body)
                    binding.cardUpdateCheckNow.isEnabled = line.actionable
                    binding.cardUpdateCheckNow.setOnClickListener {
                        if (status.phase == UpdateNoticePhase.Available) {
                            openExternalUrl(status.downloadUrl)
                        } else {
                            updateNotices.checkNow()
                        }
                    }
                }
            }
        }
        binding.switchUpdateChecks.setOnCheckedChangeListener { _, isChecked ->
            updateNotices.setChecksEnabled(isChecked)
        }
    }

    private fun chooseChip(mode: ThemeMode) {
        val chipId =
            when (mode) {
                ThemeMode.LIGHT -> R.id.chipThemeLight
                ThemeMode.DARK -> R.id.chipThemeDark
                ThemeMode.SYSTEM -> R.id.chipThemeSystem
            }
        binding.chipGroupTheme.check(chipId)
    }

    private fun formatVersion(): String = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"
}
