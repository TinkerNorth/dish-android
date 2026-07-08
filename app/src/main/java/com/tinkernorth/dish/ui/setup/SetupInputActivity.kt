// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySetupInputBinding
import com.tinkernorth.dish.databinding.SetupChoiceRowBinding
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Entry point of the guided flow and the app's first-run destination. Each
// input opens its own branch; all branches rejoin at the connection step.
@AndroidEntryPoint
class SetupInputActivity : BaseGamepadHostActivity() {
    @Inject lateinit var onboarding: OnboardingPreferenceStore

    private lateinit var binding: ActivitySetupInputBinding
    private val nav by lazy { DishNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySetupInputBinding::inflate)
        setupDishToolbar(binding.toolbar)
        wireSetupSkip(binding.toolbar, onboarding)
        binding.breadcrumb.applyStep(SETUP_STEP_INPUT)

        bindChoice(
            binding.cardWired,
            R.drawable.ic_usb,
            R.string.setup_input_wired_title,
            R.string.setup_input_wired_body,
            R.string.setup_input_badge_best,
        ) { nav.toSetupUsb() }
        bindChoice(
            binding.cardBluetooth,
            R.drawable.ic_gamepad,
            R.string.setup_input_bluetooth_title,
            R.string.setup_input_bluetooth_body,
            badge = null,
        ) { nav.toSetupBluetoothController() }
        bindChoice(
            binding.cardOnscreen,
            R.drawable.ic_phone,
            R.string.setup_input_onscreen_title,
            R.string.setup_input_onscreen_body,
            badge = null,
        ) { nav.toSetupConnection(SetupFlow.INPUT_ONSCREEN, VIRTUAL_SLOT_ID) }
    }

    private fun bindChoice(
        row: SetupChoiceRowBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes body: Int,
        @StringRes badge: Int?,
        onClick: () -> Unit,
    ) {
        row.choiceIcon.setImageResource(icon)
        row.choiceTitle.setText(title)
        row.choiceBody.setText(body)
        if (badge == null) {
            row.choiceBadge.visibility = View.GONE
        } else {
            row.choiceBadge.visibility = View.VISIBLE
            row.choiceBadge.setText(badge)
        }
        row.choiceCard.setOnClickListener { onClick() }
    }
}
