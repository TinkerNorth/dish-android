// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityHelpBinding
import com.tinkernorth.dish.databinding.HelpFaqRowBinding
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.attachDonatePill
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HelpActivity : AppCompatActivity() {
    @Inject lateinit var notifications: DishNotifications

    private lateinit var binding: ActivityHelpBinding
    private val nav by lazy { DishNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        attachDonatePill()

        bindRunSetupCard()
        bindSectionLabels()
        bindFaqRows()
        bindLinkCards()
    }

    private fun bindRunSetupCard() {
        binding.cardRowRunSetup.cardRowIcon.setImageResource(R.drawable.ic_route)
        binding.cardRowRunSetup.cardRowTitle.setText(R.string.help_run_setup_title)
        binding.cardRowRunSetup.cardRowSubtitle.setText(R.string.help_run_setup_body)
        binding.cardRunSetup.setOnClickListener { nav.toSetupInput() }
    }

    private fun bindSectionLabels() {
        binding.sectionConcepts.labelSection.setText(R.string.help_section_concepts)
        binding.sectionPerformance.labelSection.setText(R.string.help_section_performance)
        binding.sectionTrouble.labelSection.setText(R.string.help_section_troubleshooting)
        binding.sectionAbout.labelSection.setText(R.string.help_section_about)
    }

    private fun bindFaqRows() {
        bindFaq(binding.faqWhatIsDish, R.string.help_q_what_is_dish, R.string.help_a_what_is_dish)
        bindFaq(binding.faqWhatIsSatellite, R.string.help_q_what_is_satellite, R.string.help_a_what_is_satellite)
        bindFaq(binding.faqLanVsBluetooth, R.string.help_q_lan_vs_bluetooth, R.string.help_a_lan_vs_bluetooth)
        bindFaq(binding.faqControllerModes, R.string.help_q_controller_modes, R.string.help_a_controller_modes)
        bindFaq(binding.faqMotionTouchpad, R.string.help_q_motion_touchpad, R.string.help_a_motion_touchpad)

        bindFaq(binding.faqBestSetup, R.string.help_q_best_setup, R.string.help_a_best_setup)
        bindFaq(binding.faqWhyLanLower, R.string.help_q_why_lan_lower, R.string.help_a_why_lan_lower)
        bindFaq(binding.faqWiredBetter, R.string.help_q_wired_better, R.string.help_a_wired_better)

        bindFaq(binding.faqNoSatellites, R.string.help_q_no_satellites, R.string.help_a_no_satellites)
        bindFaq(binding.faqPinRejected, R.string.help_q_pin_rejected, R.string.help_a_pin_rejected)
        bindFaq(binding.faqDisconnects, R.string.help_q_disconnects, R.string.help_a_disconnects)
        bindFaq(binding.faqNoMotion, R.string.help_q_no_motion, R.string.help_a_no_motion)
        bindFaq(binding.faqBatteryDrain, R.string.help_q_battery_drain, R.string.help_a_battery_drain)

        bindFaq(binding.faqOpenSource, R.string.help_q_open_source, R.string.help_a_open_source)
        bindFaq(binding.faqPrivacy, R.string.help_q_privacy, R.string.help_a_privacy)
    }

    private fun bindFaq(
        row: HelpFaqRowBinding,
        @StringRes question: Int,
        @StringRes answer: Int,
    ) {
        row.faqQuestion.setText(question)
        row.faqAnswer.setText(answer)
        row.faqRowRoot.setOnClickListener { toggleFaq(row) }
    }

    private fun toggleFaq(row: HelpFaqRowBinding) {
        val expanding = !row.faqAnswer.isVisible
        row.faqAnswer.isVisible = expanding
        val rotation = if (expanding) 180f else 0f
        val duration = resources.getInteger(R.integer.motion_duration_medium).toLong() / 2L
        row.faqChevron
            .animate()
            .rotation(rotation)
            .setDuration(duration)
            .start()
    }

    private fun bindLinkCards() {
        binding.cardRowHelpPrivacy.cardRowIcon.setImageResource(R.drawable.ic_shield)
        binding.cardRowHelpPrivacy.cardRowTitle.setText(R.string.help_view_privacy_policy)
        binding.cardRowHelpPrivacy.cardRowSubtitle.text =
            getString(R.string.url_privacy_policy)
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")
        binding.cardHelpPrivacy.setOnClickListener {
            openExternalUrl(getString(R.string.url_privacy_policy))
        }

        binding.cardRowHelpGithub.cardRowIcon.setImageResource(R.drawable.ic_open_in_new)
        binding.cardRowHelpGithub.cardRowTitle.setText(R.string.help_view_github)
        binding.cardRowHelpGithub.cardRowSubtitle.text =
            getString(R.string.url_github)
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")
        binding.cardHelpGithub.setOnClickListener {
            openExternalUrl(getString(R.string.url_github))
        }
    }

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
