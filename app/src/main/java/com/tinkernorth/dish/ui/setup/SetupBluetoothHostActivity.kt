// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivitySetupBluetoothHostBinding
import com.tinkernorth.dish.databinding.SetupChoiceRowBinding
import com.tinkernorth.dish.databinding.SetupTypeCardBinding
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 3 Bluetooth host (design 5H). Owns the two Android-only pieces the
// ViewModel can't: the runtime BLUETOOTH grant prompt and the system
// make-discoverable prompt (the passkey is a pure OS dialog, no UI of ours).
// Everything else (host list, type lock, advertising session, the proceed gate)
// lives in the ViewModel.
@AndroidEntryPoint
class SetupBluetoothHostActivity : AppCompatActivity() {
    @Inject lateinit var onboarding: OnboardingPreferenceStore

    @Inject lateinit var notifications: DishNotifications

    private lateinit var binding: ActivitySetupBluetoothHostBinding
    private val viewModel: SetupBluetoothHostViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            viewModel.onPermissionResult()
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onDiscoverableResult(result.resultCode != Activity.RESULT_CANCELED)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBluetoothHostBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        binding.breadcrumb.applyStep(SETUP_STEP_DESTINATION)

        viewModel.bindArgs(intent.getStringExtra(SetupFlow.EXTRA_SLOT_ID).orEmpty())

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnGrant.setOnClickListener { requestBluetoothPermission() }
        binding.btnTryAgain.setOnClickListener { requestDiscoverable() }
        binding.rowPairNew.choiceCard.setOnClickListener { viewModel.onPairNewDevice() }

        bindPairNewRow()
        bindTypeCard(binding.cardXbox, BluetoothGamepad.GamepadProfile.XBOX)
        bindTypeCard(binding.cardPlaystation, BluetoothGamepad.GamepadProfile.PLAYSTATION)

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        observe()
    }

    override fun onStart() {
        super.onStart()
        // The OS never broadcasts a grant/revoke; re-poll whenever we return.
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
                        is SetupBluetoothHostViewModel.Event.RequestDiscoverable -> requestDiscoverable()
                        is SetupBluetoothHostViewModel.Event.Done ->
                            finishToDashboard(event.hostName, getString(typeTitleRes(event.profile)))
                    }
                }
            }
        }
    }

    private fun render(state: SetupBluetoothHostViewModel.State) {
        binding.groupPickPc.visibility = visibleIf(state.stage == SetupBluetoothHostViewModel.Stage.PICK_PC)
        binding.groupPermission.visibility = visibleIf(state.stage == SetupBluetoothHostViewModel.Stage.PERMISSION)
        binding.groupPickType.visibility = visibleIf(state.stage == SetupBluetoothHostViewModel.Stage.PICK_TYPE)
        binding.groupAdvertising.visibility = visibleIf(state.stage == SetupBluetoothHostViewModel.Stage.ADVERTISING)

        when (state.stage) {
            SetupBluetoothHostViewModel.Stage.PICK_PC -> {
                binding.tvTitle.setText(R.string.setup_bth_pick_pc_title)
                renderHosts(state.hosts)
            }
            SetupBluetoothHostViewModel.Stage.PERMISSION ->
                binding.tvTitle.setText(R.string.setup_bth_permission_title)
            SetupBluetoothHostViewModel.Stage.PICK_TYPE -> {
                binding.tvTitle.setText(R.string.setup_bth_pick_type_title)
                renderTypeTables(state.hasGyro)
            }
            SetupBluetoothHostViewModel.Stage.ADVERTISING -> {
                binding.tvTitle.setText(R.string.setup_bth_advertising_heading)
                renderAdvertising(state)
            }
        }
    }

    private fun renderHosts(hosts: List<SetupBluetoothHostViewModel.HostRow>) {
        val container = binding.hostContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        hosts.forEach { host ->
            val row = SetupChoiceRowBinding.inflate(inflater, container, false)
            row.choiceIcon.setImageResource(R.drawable.ic_pc_monitor)
            row.choiceTitle.text = host.name
            row.choiceBadge.visibility = View.VISIBLE
            row.choiceBadge.text = getString(typeBadgeRes(host.profile))
            row.choiceBody.setText(R.string.setup_bth_host_remembered)
            row.choiceCard.setOnClickListener { viewModel.onHostSelected(host) }
            container.addView(row.root)
        }
    }

    private fun bindPairNewRow() {
        binding.rowPairNew.choiceIcon.setImageResource(R.drawable.ic_add)
        binding.rowPairNew.choiceTitle.setText(R.string.setup_bth_pair_new_title)
        binding.rowPairNew.choiceBody.setText(R.string.setup_bth_pair_new_body)
        binding.rowPairNew.choiceBadge.visibility = View.GONE
    }

    private fun bindTypeCard(
        card: SetupTypeCardBinding,
        profile: BluetoothGamepad.GamepadProfile,
    ) {
        card.typeTitle.setText(typeTitleRes(profile))
        card.typeCard.setOnClickListener { viewModel.onTypeChosen(profile) }
    }

    // A Bluetooth host is never a Satellite, so motion/touchpad stay off; only
    // gyro presence varies the table, and that's fixed per device.
    private fun renderTypeTables(hasGyro: Boolean) {
        binding.cardXbox.capabilityContainer.bindCapabilityRows(
            SetupCapability.rows(
                isPlayStation = false,
                destinationIsSatellite = false,
                hasDestination = true,
                hasGyro = hasGyro,
            ),
        )
        binding.cardPlaystation.capabilityContainer.bindCapabilityRows(
            SetupCapability.rows(
                isPlayStation = true,
                destinationIsSatellite = false,
                hasDestination = true,
                hasGyro = hasGyro,
            ),
        )
    }

    private fun renderAdvertising(state: SetupBluetoothHostViewModel.State) {
        val profile = state.advertisingProfile ?: return
        binding.tvAdvertisingType.text = getString(R.string.setup_bth_advertising_type, getString(typeTitleRes(profile)))
        binding.tvAdvertisingBody.text = getString(R.string.setup_bth_advertising_steps, profile.sdpName)
        binding.tvDiscoverable.setText(
            if (state.discoverable) R.string.setup_bth_discoverable_on else R.string.setup_bth_discoverable_waiting,
        )
        binding.icDiscoverable.setImageResource(
            if (state.discoverable) R.drawable.ic_bluetooth_connected else R.drawable.ic_bluetooth_searching,
        )
        binding.icDiscoverable.setColorFilter(
            getColor(if (state.discoverable) R.color.colorPrimary else R.color.colorMuted),
        )
        // The system discoverable prompt can be denied or dismissed with no result
        // we can act on; offer a retry so the user is never stranded here.
        binding.btnTryAgain.visibility = visibleIf(!state.discoverable)
    }

    private fun requestBluetoothPermission() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
        )
    }

    private fun requestDiscoverable() {
        // Pre-S has no runtime discoverable contract worth prompting; the legacy
        // adapter is already discoverable enough for a directed pairing, so treat
        // it as granted and let the bond proceed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            viewModel.onDiscoverableResult(true)
            return
        }
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        discoverableLauncher.launch(intent)
    }

    private fun handleBack() {
        if (!viewModel.back()) finish()
    }

    // A Bluetooth host needs no Stage 4: the bind already happened on connect, so
    // post the success toast for the dashboard and finish, like SetupConfigureActivity.
    private fun finishToDashboard(
        hostName: String,
        controllerName: String,
    ) {
        notifications.postDeferred(
            severity = DishNotification.Severity.SUCCESS,
            title = getString(R.string.setup_cfg_done_title),
            body = getString(R.string.setup_cfg_done_body, controllerName, hostName),
        )
        onboarding.markWelcomeCompleted()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun typeTitleRes(profile: BluetoothGamepad.GamepadProfile): Int =
        when (profile) {
            BluetoothGamepad.GamepadProfile.XBOX -> R.string.setup_bth_type_xbox
            BluetoothGamepad.GamepadProfile.PLAYSTATION -> R.string.setup_bth_type_playstation
        }

    private fun typeBadgeRes(profile: BluetoothGamepad.GamepadProfile): Int =
        when (profile) {
            BluetoothGamepad.GamepadProfile.XBOX -> R.string.setup_bth_badge_xbox
            BluetoothGamepad.GamepadProfile.PLAYSTATION -> R.string.setup_bth_badge_playstation
        }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE

    private companion object {
        const val DISCOVERABLE_SECONDS = 120
    }
}
