// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySettingsBinding
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.CrashReportingStore
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
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

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
        binding.tvPrivacyPolicySubtitle.text =
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
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
