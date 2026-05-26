// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.viewpager2.widget.ViewPager2
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityWelcomeBinding
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WelcomeActivity : AppCompatActivity() {
    @Inject lateinit var onboardingStore: OnboardingPreferenceStore

    @Inject lateinit var notifications: DishNotifications

    private lateinit var binding: ActivityWelcomeBinding
    private val nav by lazy { DishNavigator(this) }

    private val stepIndicatorDots: MutableList<View> = mutableListOf()

    private val pages: List<WelcomePage> by lazy {
        listOf(
            WelcomePage(
                hero = R.drawable.ic_dish_connected,
                eyebrow = R.string.welcome_step1_eyebrow,
                title = R.string.welcome_step1_title,
                body = R.string.welcome_step1_body,
                heroContentDescription = R.string.welcome_step1_hero_desc,
            ),
            WelcomePage(
                hero = R.drawable.ic_phone_link,
                eyebrow = R.string.welcome_step2_eyebrow,
                title = R.string.welcome_step2_title,
                body = R.string.welcome_step2_body,
                heroContentDescription = R.string.welcome_step2_hero_desc,
            ),
            WelcomePage(
                hero = R.drawable.ic_pc_monitor,
                eyebrow = R.string.welcome_step3_eyebrow,
                title = R.string.welcome_step3_title,
                body = R.string.welcome_step3_body,
                heroContentDescription = R.string.welcome_step3_hero_desc,
                extraCtaLabel = R.string.welcome_step3_satellite_link_label,
                extraCtaTag = CTA_OPEN_SATELLITE,
            ),
            WelcomePage(
                hero = R.drawable.ic_check_circle,
                eyebrow = R.string.welcome_step4_eyebrow,
                title = R.string.welcome_step4_title,
                body = R.string.welcome_step4_body,
                heroContentDescription = R.string.welcome_step4_hero_desc,
                extraCtaLabel = R.string.welcome_step4_launch_wizard,
                extraCtaTag = CTA_LAUNCH_WIZARD,
            ),
        )
    }

    private val pageChangeCallback =
        object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateStepIndicator(position)
                updateNavButtons(position)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()

        buildStepIndicator()
        binding.welcomePager.adapter =
            WelcomePagerAdapter(pages) { page -> handleExtraCta(page) }
        binding.welcomePager.registerOnPageChangeCallback(pageChangeCallback)

        val initial = savedInstanceState?.getInt(STATE_STEP, 0)?.coerceIn(0, pages.lastIndex) ?: 0
        binding.welcomePager.setCurrentItem(initial, false)
        updateStepIndicator(initial)
        updateNavButtons(initial)

        binding.btnWelcomeSkip.setOnClickListener { completeAndLaunch(launchWizardAfter = false) }
        binding.btnWelcomeBack.setOnClickListener {
            val current = binding.welcomePager.currentItem
            if (current > 0) binding.welcomePager.setCurrentItem(current - 1, true)
        }
        binding.btnWelcomeNext.setOnClickListener {
            val current = binding.welcomePager.currentItem
            if (current < pages.lastIndex) {
                binding.welcomePager.setCurrentItem(current + 1, true)
            } else {
                completeAndLaunch(launchWizardAfter = false)
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = binding.welcomePager.currentItem
                    if (current > 0) {
                        binding.welcomePager.setCurrentItem(current - 1, true)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onDestroy() {
        binding.welcomePager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, binding.welcomePager.currentItem)
    }

    private fun buildStepIndicator() {
        val container = binding.welcomeStepIndicator
        container.removeAllViews()
        stepIndicatorDots.clear()
        val mutedColor = ContextCompat.getColor(this, R.color.colorMuted)
        val heightPx = resources.getDimensionPixelSize(R.dimen.step_indicator_height)
        val inactivePx = resources.getDimensionPixelSize(R.dimen.step_indicator_dot_width)
        val marginPx = resources.getDimensionPixelSize(R.dimen.spacing_xs)
        pages.indices.forEach { _ ->
            val dot =
                View(this).apply {
                    background = ContextCompat.getDrawable(this@WelcomeActivity, R.drawable.bg_step_indicator)
                    background.setTint(mutedColor)
                }
            val lp =
                LinearLayout.LayoutParams(inactivePx, heightPx).apply {
                    marginStart = marginPx
                    marginEnd = marginPx
                }
            container.addView(dot, lp)
            stepIndicatorDots += dot
        }
    }

    private fun updateStepIndicator(activeIndex: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val mutedColor = ContextCompat.getColor(this, R.color.colorMuted)
        val activePx = resources.getDimensionPixelSize(R.dimen.step_indicator_dot_width_active)
        val inactivePx = resources.getDimensionPixelSize(R.dimen.step_indicator_dot_width)
        stepIndicatorDots.forEachIndexed { index, view ->
            val active = index == activeIndex
            view.background.setTint(if (active) activeColor else mutedColor)
            val lp = view.layoutParams
            lp.width = if (active) activePx else inactivePx
            view.layoutParams = lp
            view.contentDescription = getString(R.string.welcome_step_indicator_desc, index + 1, pages.size)
        }
    }

    private fun updateNavButtons(position: Int) {
        binding.btnWelcomeBack.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        val isFinalPage = position == pages.lastIndex
        binding.btnWelcomeNext.setText(
            if (isFinalPage) R.string.welcome_get_started else R.string.welcome_next,
        )
        binding.btnWelcomeSkip.visibility = if (isFinalPage) View.INVISIBLE else View.VISIBLE
    }

    private fun handleExtraCta(page: WelcomePage) {
        when (page.extraCtaTag) {
            CTA_OPEN_SATELLITE -> openExternalUrl(getString(R.string.url_satellite))
            CTA_LAUNCH_WIZARD -> completeAndLaunch(launchWizardAfter = true)
        }
    }

    private fun completeAndLaunch(launchWizardAfter: Boolean) {
        onboardingStore.markWelcomeCompleted()
        if (launchWizardAfter) {
            nav.toSetupWizard()
        } else {
            val intent =
                Intent(this, com.tinkernorth.dish.ui.main.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            startActivity(intent)
        }
        finish()
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

    private companion object {
        const val STATE_STEP = "welcome_current_step"
        const val CTA_OPEN_SATELLITE = "open_satellite"
        const val CTA_LAUNCH_WIZARD = "launch_wizard"
    }
}
