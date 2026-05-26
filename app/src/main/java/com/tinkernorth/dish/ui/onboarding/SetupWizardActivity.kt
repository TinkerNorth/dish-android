// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.onboarding

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySetupWizardBinding
import com.tinkernorth.dish.databinding.WizardOptionCardBinding
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SetupWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupWizardBinding
    private val nav by lazy { DishNavigator(this) }

    private enum class WizardConnection { LAN, BT }

    private enum class WizardController { VIRTUAL, USB, PHYSICAL_BT }

    private var step: Int = 1
    private var pickedConnection: WizardConnection? = null
    private var pickedController: WizardController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()

        savedInstanceState?.let {
            step = it.getInt(STATE_STEP, 1).coerceIn(1, MAX_STEPS)
            pickedConnection = it.getString(STATE_CONNECTION)?.let(WizardConnection::valueOf)
            pickedController = it.getString(STATE_CONTROLLER)?.let(WizardController::valueOf)
        }

        bindStep1OptionCards()
        bindStep2OptionCards()
        bindNavButtons()
        applyStep(step)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (step > 1) {
                        applyStep(step - 1)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
        pickedConnection?.let { outState.putString(STATE_CONNECTION, it.name) }
        pickedController?.let { outState.putString(STATE_CONTROLLER, it.name) }
    }

    private fun bindStep1OptionCards() {
        setOption(
            binding.optionLan,
            icon = R.drawable.ic_wifi,
            title = R.string.wizard_option_lan_title,
            body = R.string.wizard_option_lan_body,
            recommendedLabel = R.string.wizard_option_lan_pill_recommended,
        ) { selectConnection(WizardConnection.LAN) }

        setOption(
            binding.optionBt,
            icon = R.drawable.ic_bluetooth,
            title = R.string.wizard_option_bt_title,
            body = R.string.wizard_option_bt_body,
            recommendedLabel = null,
        ) { selectConnection(WizardConnection.BT) }
    }

    private fun bindStep2OptionCards() {
        setOption(
            binding.optionVirtual,
            icon = R.drawable.ic_phone,
            title = R.string.wizard_option_virtual_title,
            body = R.string.wizard_option_virtual_body,
            recommendedLabel = null,
        ) { selectController(WizardController.VIRTUAL) }

        setOption(
            binding.optionUsb,
            icon = R.drawable.ic_usb,
            title = R.string.wizard_option_usb_title,
            body = R.string.wizard_option_usb_body,
            recommendedLabel = R.string.wizard_option_usb_pill_recommended,
        ) { selectController(WizardController.USB) }

        setOption(
            binding.optionPhysBt,
            icon = R.drawable.ic_gamepad,
            title = R.string.wizard_option_phys_bt_title,
            body = R.string.wizard_option_phys_bt_body,
            recommendedLabel = null,
        ) { selectController(WizardController.PHYSICAL_BT) }
    }

    private fun setOption(
        binding: WizardOptionCardBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes body: Int,
        @StringRes recommendedLabel: Int?,
        onClick: () -> Unit,
    ) {
        binding.optionIcon.setImageResource(icon)
        binding.optionTitle.setText(title)
        binding.optionBody.setText(body)
        if (recommendedLabel != null) {
            binding.optionRecommendedPill.setText(recommendedLabel)
            binding.optionRecommendedPill.isVisible = true
        } else {
            binding.optionRecommendedPill.isVisible = false
        }
        binding.cardWizardOptionRoot.setOnClickListener { onClick() }
    }

    private fun bindNavButtons() {
        binding.btnWizardBack.setOnClickListener {
            if (step > 1) applyStep(step - 1) else finish()
        }
        binding.btnWizardNext.setOnClickListener { onPrimaryAction() }
    }

    private fun selectConnection(connection: WizardConnection) {
        pickedConnection = connection
        applySelectionStrokes()
        refreshPrimaryAction()
    }

    private fun selectController(controller: WizardController) {
        pickedController = controller
        applySelectionStrokes()
        refreshPrimaryAction()
    }

    private fun applySelectionStrokes() {
        applyStroke(binding.optionLan.cardWizardOptionRoot, pickedConnection == WizardConnection.LAN)
        applyStroke(binding.optionBt.cardWizardOptionRoot, pickedConnection == WizardConnection.BT)
        applyStroke(binding.optionVirtual.cardWizardOptionRoot, pickedController == WizardController.VIRTUAL)
        applyStroke(binding.optionUsb.cardWizardOptionRoot, pickedController == WizardController.USB)
        applyStroke(binding.optionPhysBt.cardWizardOptionRoot, pickedController == WizardController.PHYSICAL_BT)
    }

    private fun applyStroke(
        card: MaterialCardView,
        selected: Boolean,
    ) {
        val color =
            if (selected) {
                ContextCompat.getColor(this, R.color.colorPrimary)
            } else {
                ContextCompat.getColor(this, R.color.colorCardStroke)
            }
        val width =
            resources.getDimensionPixelSize(
                if (selected) R.dimen.wizard_option_stroke_selected else R.dimen.border_thin,
            )
        card.strokeColor = color
        card.strokeWidth = width
    }

    private fun applyStep(target: Int) {
        step = target.coerceIn(1, MAX_STEPS)

        binding.tvWizardProgress.text = getString(R.string.wizard_step_progress, step, MAX_STEPS)

        binding.step1Body.isVisible = step == 1
        binding.step2Body.isVisible = step == 2
        binding.step3Body.isVisible = step == 3

        when (step) {
            1 -> {
                binding.tvWizardEyebrow.setText(R.string.wizard_step1_eyebrow)
                binding.tvWizardTitle.setText(R.string.wizard_step1_title)
                binding.tvWizardBody.setText(R.string.wizard_step1_body)
            }
            2 -> {
                binding.tvWizardEyebrow.setText(R.string.wizard_step2_eyebrow)
                binding.tvWizardTitle.setText(R.string.wizard_step2_title)
                binding.tvWizardBody.setText(R.string.wizard_step2_body)
            }
            3 -> {
                binding.tvWizardEyebrow.setText(R.string.wizard_step3_eyebrow)
                binding.tvWizardTitle.setText(R.string.wizard_step3_title)
                binding.tvWizardBody.text = ""
                renderSummary()
            }
        }

        applySelectionStrokes()
        refreshPrimaryAction()
    }

    private fun renderSummary() {
        binding.tvSummaryConnection.text =
            when (pickedConnection) {
                WizardConnection.LAN -> getString(R.string.wizard_option_lan_title)
                WizardConnection.BT -> getString(R.string.wizard_option_bt_title)
                null -> ""
            }
        binding.tvSummaryController.text =
            when (pickedController) {
                WizardController.VIRTUAL -> getString(R.string.wizard_option_virtual_title)
                WizardController.USB -> getString(R.string.wizard_option_usb_title)
                WizardController.PHYSICAL_BT -> getString(R.string.wizard_option_phys_bt_title)
                null -> ""
            }
        binding.tvWizardNextSteps.setText(nextStepsCopy())
    }

    @StringRes
    private fun nextStepsCopy(): Int =
        when {
            pickedConnection == WizardConnection.LAN && pickedController == WizardController.VIRTUAL ->
                R.string.wizard_next_lan_virtual
            pickedConnection == WizardConnection.LAN && pickedController == WizardController.USB ->
                R.string.wizard_next_lan_usb
            pickedConnection == WizardConnection.LAN && pickedController == WizardController.PHYSICAL_BT ->
                R.string.wizard_next_lan_phys_bt
            pickedConnection == WizardConnection.BT && pickedController == WizardController.VIRTUAL ->
                R.string.wizard_next_bt_virtual
            pickedConnection == WizardConnection.BT && pickedController == WizardController.USB ->
                R.string.wizard_next_bt_usb
            pickedConnection == WizardConnection.BT && pickedController == WizardController.PHYSICAL_BT ->
                R.string.wizard_next_bt_phys_bt
            else -> R.string.wizard_step3_title
        }

    private fun isCombinationViable(): Boolean =
        pickedConnection != WizardConnection.BT ||
            pickedController == WizardController.VIRTUAL

    private fun refreshPrimaryAction() {
        when (step) {
            1 -> {
                binding.btnWizardNext.isEnabled = pickedConnection != null
                binding.btnWizardNext.setText(R.string.wizard_action_next)
                binding.btnWizardBack.isVisible = false
            }
            2 -> {
                binding.btnWizardNext.isEnabled = pickedController != null
                binding.btnWizardNext.setText(R.string.wizard_action_next)
                binding.btnWizardBack.isVisible = true
            }
            3 -> {
                binding.btnWizardNext.isEnabled = true
                binding.btnWizardNext.setText(
                    if (isCombinationViable()) {
                        R.string.wizard_action_finish
                    } else {
                        R.string.wizard_finish_review
                    },
                )
                binding.btnWizardBack.isVisible = true
            }
        }
    }

    private fun onPrimaryAction() {
        when (step) {
            1 -> if (pickedConnection != null) applyStep(2)
            2 -> if (pickedController != null) applyStep(3)
            3 -> {
                if (isCombinationViable()) {
                    nav.toConnections()
                    finish()
                } else {
                    applyStep(1)
                }
            }
        }
    }

    private companion object {
        const val MAX_STEPS = 3
        const val STATE_STEP = "wizard_current_step"
        const val STATE_CONNECTION = "wizard_picked_connection"
        const val STATE_CONTROLLER = "wizard_picked_controller"
    }
}
