// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySettingsBinding
import com.tinkernorth.dish.source.store.CrashReportingStore
import com.tinkernorth.dish.source.store.ThemeMode
import com.tinkernorth.dish.source.store.ThemePreferenceStore
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.attachDonatePill
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.diagnostics.DiagnosticsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : BaseGamepadHostActivity() {
    @Inject lateinit var crashReportingStore: CrashReportingStore

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    private lateinit var binding: ActivitySettingsBinding
    private val nav by lazy { DishNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySettingsBinding::inflate)
        setupDishToolbar(binding.toolbar)
        attachDonatePill()

        binding.sectionSetup.labelSection.setText(R.string.settings_section_setup)
        binding.sectionAppearance.labelSection.setText(R.string.settings_section_appearance)
        binding.sectionDiagnostics.labelSection.setText(R.string.settings_section_diagnostics)
        binding.sectionAbout.labelSection.setText(R.string.settings_section_about)

        binding.cardRowSetupWizard.cardRowIcon.setImageResource(R.drawable.ic_route)
        binding.cardRowSetupWizard.cardRowTitle.setText(R.string.settings_setup_wizard_title)
        binding.cardRowSetupWizard.cardRowSubtitle.setText(R.string.settings_setup_wizard_body)
        binding.cardSetupWizard.setOnClickListener { nav.toSetupInput() }

        binding.cardRowHelp.cardRowIcon.setImageResource(R.drawable.ic_help)
        binding.cardRowHelp.cardRowTitle.setText(R.string.settings_help_title)
        binding.cardRowHelp.cardRowSubtitle.setText(R.string.settings_help_body)
        binding.cardHelp.setOnClickListener { nav.toHelp() }

        binding.cardRowAppearance.cardRowIcon.setImageResource(R.drawable.ic_contrast)
        binding.cardRowAppearance.cardRowTitle.setText(R.string.settings_appearance_title)
        binding.cardRowAppearance.cardRowSubtitle.setText(R.string.settings_appearance_body)
        chooseChip(themePreferenceStore.state.value)
        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds ->
            // selectionRequired guarantees one id, but a configuration-change rebind can briefly
            // observe an empty selection. Guard rather than crash.
            val mode =
                when (checkedIds.firstOrNull()) {
                    R.id.chipThemeLight -> ThemeMode.LIGHT
                    R.id.chipThemeDark -> ThemeMode.DARK
                    R.id.chipThemeSystem -> ThemeMode.SYSTEM
                    else -> return@setOnCheckedStateChangeListener
                }
            if (mode != themePreferenceStore.state.value) {
                themePreferenceStore.setMode(mode)
            }
        }

        binding.cardRowDiagnostics.cardRowIcon.setImageResource(R.drawable.ic_bug)
        binding.cardRowDiagnostics.cardRowTitle.setText(R.string.settings_diagnostics_title)
        binding.cardRowDiagnostics.cardRowSubtitle.setText(R.string.settings_diagnostics_body)
        binding.cardDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.cardRowCrash.cardRowIcon.setImageResource(R.drawable.ic_bug)
        binding.cardRowCrash.cardRowTitle.setText(R.string.settings_crash_reporting_title)
        binding.cardRowCrash.cardRowSubtitle.setText(R.string.settings_crash_reporting_body)

        binding.cardRowPrivacy.cardRowIcon.setImageResource(R.drawable.ic_shield)
        binding.cardRowPrivacy.cardRowTitle.setText(R.string.menu_privacy_policy)
        binding.cardRowPrivacy.cardRowSubtitle.apply {
            setTextAppearance(R.style.TextAppearance_Dish_Body_Mono)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        binding.cardRowOpenSourceLicenses.cardRowIcon.setImageResource(R.drawable.ic_license)
        binding.cardRowOpenSourceLicenses.cardRowTitle.setText(R.string.settings_open_source_licenses_title)
        binding.cardRowOpenSourceLicenses.cardRowSubtitle.setText(R.string.settings_open_source_licenses_body)
        binding.cardOpenSourceLicenses.setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }

        binding.cardRowSupport.cardRowIcon.setImageResource(R.drawable.ic_heart)
        binding.cardRowSupport.cardRowIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPulse))
        binding.cardRowSupport.cardRowTitle.setText(R.string.settings_support_title)
        binding.cardRowSupport.cardRowSubtitle.setText(R.string.settings_support_body)
        binding.cardSupport.setOnClickListener { nav.toDonate() }

        // Observe-then-bind: opposite order would re-write the persisted preference on the first frame.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                crashReportingStore.state.collect { enabled ->
                    if (binding.switchCrashReporting.isChecked != enabled) {
                        binding.switchCrashReporting.isChecked = enabled
                    }
                }
            }
        }
        binding.switchCrashReporting.setOnCheckedChangeListener { _, isChecked ->
            crashReportingStore.setEnabled(isChecked)
        }

        binding.cardPrivacyPolicy.setOnClickListener {
            openExternalUrl(getString(R.string.url_privacy_policy))
        }
        binding.cardRowPrivacy.cardRowSubtitle.text =
            getString(R.string.url_privacy_policy)
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")

        binding.tvVersion.text = formatVersion()
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

    private fun openExternalUrl(url: String) {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                notifications.warn(
                    title = getString(R.string.error_open_url),
                    body = url,
                    key = "external-url-failed",
                )
            }
    }
}
