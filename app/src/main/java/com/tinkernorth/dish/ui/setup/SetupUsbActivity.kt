// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySetupUsbBinding
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SetupUsbActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupUsbBinding
    private val viewModel: SetupUsbViewModel by viewModels()
    private val nav by lazy { DishNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupUsbBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        binding.breadcrumb.applyStep(SETUP_STEP_INPUT)

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnContinue.setOnClickListener { viewModel.continueToMode() }
        binding.cardDirect.setOnClickListener { viewModel.chooseDirect() }
        binding.cardStandard.setOnClickListener { viewModel.chooseStandard() }
        binding.btnGrant.setOnClickListener { viewModel.showPrompt() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is SetupUsbViewModel.Event.Proceed ->
                            nav.toSetupConnection(SetupFlow.INPUT_USB, event.slotId)
                    }
                }
            }
        }
    }

    private fun render(state: SetupUsbViewModel.State) {
        binding.loader.visibility = if (state.working) View.VISIBLE else View.INVISIBLE
        binding.groupDetect.visibility = visibleIf(state.stage == SetupUsbViewModel.Stage.DETECTING)
        binding.groupMode.visibility = visibleIf(state.stage == SetupUsbViewModel.Stage.MODE)
        binding.groupGrant.visibility = visibleIf(state.stage == SetupUsbViewModel.Stage.GRANTING)
        binding.btnContinue.visibility = visibleIf(state.stage == SetupUsbViewModel.Stage.DETECTING)

        when (state.stage) {
            SetupUsbViewModel.Stage.DETECTING -> {
                binding.tvTitle.setText(R.string.setup_usb_detect_title)
                binding.cardUsbDevice.visibility = visibleIf(state.present)
                binding.tvDeviceName.text = state.deviceName
                binding.tvDeviceCode.text = state.deviceCode
                binding.tvStatus.setText(if (state.present) R.string.setup_usb_status_detected else R.string.setup_usb_status_scanning)
                binding.btnContinue.isEnabled = state.present
            }
            SetupUsbViewModel.Stage.MODE -> {
                binding.tvTitle.setText(R.string.setup_usb_mode_title)
                renderMode(state.verified)
            }
            SetupUsbViewModel.Stage.GRANTING -> binding.tvTitle.setText(R.string.setup_usb_grant_title)
        }
    }

    // A verified (fast-lane) controller leads with Direct; an unverified one
    // recommends Standard and flags the mapping risk on the Direct card.
    private fun renderMode(verified: Boolean) {
        binding.calloutVerified.visibility = visibleIf(verified)
        binding.calloutUnverified.visibility = visibleIf(!verified)
        binding.pillDirect.visibility = visibleIf(verified)
        binding.pillStandard.visibility = visibleIf(!verified)
        binding.directWarningRow.visibility = visibleIf(!verified)
        binding.tvDirectBody.setText(
            if (verified) R.string.setup_usb_direct_body else R.string.setup_usb_direct_unverified_body,
        )
        binding.tvStandardBody.setText(
            if (verified) R.string.setup_usb_standard_body else R.string.setup_usb_standard_verified_body,
        )
    }

    private fun handleBack() {
        if (!viewModel.back()) finish()
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE
}
