// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.ChipGroup
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityDonateBinding
import com.tinkernorth.dish.databinding.DonateTierButtonBinding
import com.tinkernorth.dish.source.billing.BillingAvailability
import com.tinkernorth.dish.source.billing.Tier
import com.tinkernorth.dish.source.billing.TipCatalog
import com.tinkernorth.dish.source.billing.TipJarNotice
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DonateActivity : BaseGamepadHostActivity() {
    private val viewModel: DonateViewModel by viewModels()
    private lateinit var binding: ActivityDonateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityDonateBinding::inflate)
        setupDishToolbar(binding.toolbar)
        bindWhy()
        binding.manageSubscription.setOnClickListener {
            openExternalUrl(getString(R.string.url_play_subscriptions, TipCatalog.SUBSCRIPTION_PRODUCT_ID, packageName))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }
    }

    private fun render(ui: DonateUiState) {
        binding.donateStatus.isVisible = ui.availability != BillingAvailability.READY
        binding.donateStatus.setText(
            if (ui.availability == BillingAvailability.UNAVAILABLE) R.string.donate_unavailable else R.string.donate_loading,
        )
        renderTiers(binding.tipTiers, ui.tips, Tier::formattedPrice)
        renderTiers(binding.planTiers, ui.plans) { getString(R.string.donate_per_month, it.formattedPrice) }
        binding.monthlyBody.setText(if (ui.supporterActive) R.string.donate_monthly_switch else R.string.donate_monthly_body)
        binding.supporterActive.isVisible = ui.supporterActive
        binding.manageSubscription.isVisible = ui.supporterActive
        ui.notice?.let(::showNotice)
    }

    private fun renderTiers(
        group: ChipGroup,
        tiers: List<Tier>,
        label: (Tier) -> String,
    ) {
        if (group.tag == tiers) return
        group.tag = tiers
        group.removeAllViews()
        tiers.forEach { tier ->
            val button = DonateTierButtonBinding.inflate(layoutInflater, group, false).root
            button.text = label(tier)
            button.setOnClickListener { buy(tier) }
            group.addView(button)
        }
    }

    private fun buy(tier: Tier) {
        if (!viewModel.buy(this, tier)) notifications.warn(getString(R.string.donate_error))
    }

    private fun showNotice(notice: TipJarNotice) {
        when (notice) {
            TipJarNotice.PaymentPending -> notifications.info(getString(R.string.donate_pending))
            TipJarNotice.ThankedForTip -> notifications.success(getString(R.string.donate_thanks_tip))
            TipJarNotice.ThankedForMonthly -> notifications.success(getString(R.string.donate_thanks_monthly))
            TipJarNotice.Failed -> notifications.warn(getString(R.string.donate_error))
        }
        viewModel.noticeShown()
    }

    private fun bindWhy() {
        binding.whyHosting.donateWhyText.setText(R.string.donate_why_hosting)
        binding.whySigning.donateWhyText.setText(R.string.donate_why_signing)
        binding.whyPlay.donateWhyText.setText(R.string.donate_why_play)
        binding.whyTime.donateWhyText.setText(R.string.donate_why_time)
    }
}
