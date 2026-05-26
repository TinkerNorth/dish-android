// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.onboarding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tinkernorth.dish.databinding.WelcomePageBinding

data class WelcomePage(
    @DrawableRes val hero: Int,
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val heroContentDescription: Int,
    @StringRes val extraCtaLabel: Int? = null,
    val extraCtaTag: String? = null,
)

class WelcomePagerAdapter(
    private val pages: List<WelcomePage>,
    private val onExtraCta: (WelcomePage) -> Unit,
) : RecyclerView.Adapter<WelcomePagerAdapter.PageViewHolder>() {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): PageViewHolder {
        val binding =
            WelcomePageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return PageViewHolder(binding, onExtraCta)
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
    ) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class PageViewHolder(
        private val binding: WelcomePageBinding,
        private val onExtraCta: (WelcomePage) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(page: WelcomePage) {
            val context = binding.root.context
            binding.ivWelcomePageHero.setImageResource(page.hero)
            binding.ivWelcomePageHero.contentDescription = context.getString(page.heroContentDescription)
            binding.tvWelcomePageEyebrow.setText(page.eyebrow)
            binding.tvWelcomePageTitle.setText(page.title)
            binding.tvWelcomePageBody.setText(page.body)

            val ctaLabel = page.extraCtaLabel
            if (ctaLabel != null) {
                binding.btnWelcomePageExtra.setText(ctaLabel)
                binding.btnWelcomePageExtra.isVisible = true
                binding.btnWelcomePageExtra.setOnClickListener { onExtraCta(page) }
            } else {
                binding.btnWelcomePageExtra.isVisible = false
                binding.btnWelcomePageExtra.setOnClickListener(null)
            }
        }
    }
}
