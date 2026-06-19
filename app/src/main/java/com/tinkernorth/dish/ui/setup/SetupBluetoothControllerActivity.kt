// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivitySetupBluetoothControllerBinding
import com.tinkernorth.dish.databinding.SetupBtcPairedRowBinding
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 2 Bluetooth controller (design 2C). Pairing lives in the system Bluetooth
// settings; this screen gates on the runtime permission, lists the controllers
// currently connected, and advances the moment one is tapped. The Activity owns
// the permission prompt and the jump to settings, mirroring ConnectionsActivity.
@AndroidEntryPoint
class SetupBluetoothControllerActivity : AppCompatActivity() {
    @Inject lateinit var onboarding: OnboardingPreferenceStore

    private lateinit var binding: ActivitySetupBluetoothControllerBinding
    private val viewModel: SetupBluetoothControllerViewModel by viewModels()
    private val nav by lazy { DishNavigator(this) }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ ->
            viewModel.onPermissionResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBluetoothControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        wireSetupSkip(binding.toolbar, onboarding)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        binding.breadcrumb.applyStep(SETUP_STEP_INPUT)

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnGrant.setOnClickListener { requestBluetoothPermissions() }
        binding.cardPairNew.setOnClickListener { openBluetoothSettings() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        observe()
    }

    override fun onResume() {
        super.onResume()
        // The grant or a fresh pairing both happen outside this screen; re-poll on return.
        viewModel.refresh()
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
                        is SetupBluetoothControllerViewModel.Event.Proceed ->
                            nav.toSetupConnection(SetupFlow.INPUT_BLUETOOTH, event.slotId)
                    }
                }
            }
        }
    }

    private fun render(state: SetupBluetoothControllerViewModel.State) {
        binding.groupPermission.visibility = visibleIf(state.permissionMissing)
        binding.groupPaired.visibility = visibleIf(!state.permissionMissing)
        if (!state.permissionMissing) renderControllers(state.controllers)
    }

    private fun renderControllers(controllers: List<SetupBluetoothControllerViewModel.Controller>) {
        binding.tvPairedEmpty.visibility = visibleIf(controllers.isEmpty())
        val container = binding.pairedList
        container.removeAllViews()
        for (controller in controllers) {
            val rowBinding = SetupBtcPairedRowBinding.inflate(layoutInflater, container, false)
            rowBinding.pairedName.text = controller.name
            rowBinding.pairedStatus.setText(R.string.setup_btc_status_connected)
            rowBinding.pairedStatus.setTextColor(getColor(R.color.colorSuccess))
            rowBinding.pairedCard.setOnClickListener { viewModel.onControllerTapped(controller) }
            container.addView(rowBinding.root)
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Pre-S relies on install-time grants; nothing to prompt for.
            viewModel.onPermissionResult()
            return
        }
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ),
        )
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    // 2C is a single screen with no reversible sub-state, so back always exits the branch.
    private fun handleBack() {
        finish()
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE
}
