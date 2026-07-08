// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.donate

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityDonateBinding
import com.tinkernorth.dish.databinding.DonateRailCardBinding
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DonateActivity : BaseGamepadHostActivity() {
    private lateinit var binding: ActivityDonateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivityDonateBinding::inflate)
        setupDishToolbar(binding.toolbar)

        bindCta()
        bindRails()
        bindWhy()
    }

    private fun bindCta() {
        binding.btnDonateCta.text =
            getString(R.string.donate_cta_recommended, getString(R.string.donate_rail_sponsors_name))
        binding.btnDonateCta.setOnClickListener { openExternalUrl(getString(R.string.url_sponsors)) }
    }

    private fun bindRails() {
        bindRail(
            binding.railSponsors,
            nameRes = R.string.donate_rail_sponsors_name,
            blurbRes = R.string.donate_tier_sponsors_blurb,
            cadenceRes = R.string.donate_cadence_recurring,
            currenciesRes = R.string.donate_tier_sponsors_currencies,
            urlRes = R.string.url_sponsors,
            recommended = true,
        )
        bindRail(
            binding.railKofi,
            nameRes = R.string.donate_rail_kofi_name,
            blurbRes = R.string.donate_tier_kofi_blurb,
            cadenceRes = R.string.donate_cadence_one_time,
            currenciesRes = R.string.donate_tier_kofi_currencies,
            urlRes = R.string.url_kofi,
            recommended = false,
        )
        bindRail(
            binding.railBmac,
            nameRes = R.string.donate_rail_bmac_name,
            blurbRes = R.string.donate_tier_bmac_blurb,
            cadenceRes = R.string.donate_cadence_either,
            currenciesRes = R.string.donate_tier_bmac_currencies,
            urlRes = R.string.url_bmac,
            recommended = false,
        )
    }

    private fun bindRail(
        row: DonateRailCardBinding,
        @StringRes nameRes: Int,
        @StringRes blurbRes: Int,
        @StringRes cadenceRes: Int,
        @StringRes currenciesRes: Int,
        @StringRes urlRes: Int,
        recommended: Boolean,
    ) {
        val name = getString(nameRes)
        row.donateRailTitle.text = name
        row.donateRailBlurb.setText(blurbRes)
        row.donateRailCadenceValue.setText(cadenceRes)
        row.donateRailPaysValue.setText(currenciesRes)
        row.donateRailVisit.text = getString(R.string.donate_visit, name)
        row.donateRailBadge.isVisible = recommended
        row.root.setOnClickListener { openExternalUrl(getString(urlRes)) }
    }

    private fun bindWhy() {
        binding.whyHosting.donateWhyText.setText(R.string.donate_why_hosting)
        binding.whySigning.donateWhyText.setText(R.string.donate_why_signing)
        binding.whyPlay.donateWhyText.setText(R.string.donate_why_play)
        binding.whyTime.donateWhyText.setText(R.string.donate_why_time)
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
