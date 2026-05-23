// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySettingsBinding
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.CrashReportingStore
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-screen settings host. Two sections rendered per the Claude Design
 * "Settings" handoff:
 *   - Diagnostics: crash-reporting opt-in toggle. The switch position is
 *     reactive to [CrashReportingStore.state] so it stays consistent even if
 *     the preference flips from outside this screen.
 *   - About: privacy-policy link (opens the policy URL in the user's browser)
 *     and a mono version row sourced from [BuildConfig].
 *
 * Reachable from the gear icon on MainActivity (see `activity_main.xml`'s
 * `btnSettings`). The Connections screen no longer hosts an overflow entry
 * point — settings live exclusively on the main screen now.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject lateinit var crashReportingStore: CrashReportingStore

    @Inject lateinit var notifications: DishNotifications

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars()

        // Section header labels live in `section_header.xml` and are
        // populated here so the include's TextView (id `labelSection`) can
        // be shared across the dashboard, connections, and settings screens
        // without duplicating XML.
        binding.sectionDiagnostics.labelSection.setText(R.string.settings_section_diagnostics)
        binding.sectionAbout.labelSection.setText(R.string.settings_section_about)

        // Crash-reporting card — composite `card_row_icon_label_value.xml`
        // owns the leading icon + title/subtitle column; the Switch sits
        // alongside in `activity_settings.xml` as a sibling so this card's
        // multi-line gravity stays at the callsite.
        binding.cardRowCrash.cardRowIcon.setImageResource(R.drawable.ic_bug)
        binding.cardRowCrash.cardRowTitle.setText(R.string.settings_crash_reporting_title)
        binding.cardRowCrash.cardRowSubtitle.setText(R.string.settings_crash_reporting_body)

        // Privacy-policy card — same composite, but the subtitle adopts
        // `Body.Mono` (on-surface mono URL tone) instead of the composite's
        // default `Body`. Single-line + end-ellipsize because the URL can
        // exceed the row width on narrow devices.
        binding.cardRowPrivacy.cardRowIcon.setImageResource(R.drawable.ic_shield)
        binding.cardRowPrivacy.cardRowTitle.setText(R.string.menu_privacy_policy)
        binding.cardRowPrivacy.cardRowSubtitle.apply {
            setTextAppearance(R.style.TextAppearance_Dish_Body_Mono)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        // The toggle is observed-then-bound: render the persisted state, then
        // attach the listener. Doing it in the opposite order would re-write
        // the persisted preference on the first frame (a no-op for value but
        // a useless write).
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
        // Display the privacy URL minus the scheme — the row title says
        // "Privacy policy", the subtitle is just the host+path so users can
        // see where the link actually points before tapping.
        binding.cardRowPrivacy.cardRowSubtitle.text =
            getString(R.string.url_privacy_policy)
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")

        binding.tvVersion.text = formatVersion()
    }

    /**
     * "1.0 · build 1" style — versionName + versionCode separated by a middle
     * dot, matching the design's MetaRow value formatting. Both values are
     * baked at compile time by app/build.gradle.kts:resolveVersion().
     */
    private fun formatVersion(): String = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"

    /** Open [url] in the user's default browser. Surfaces a DishNotifications
     *  warn banner if no Activity can handle the intent (rare — usually means
     *  a stripped-down device with no browser installed). */
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
