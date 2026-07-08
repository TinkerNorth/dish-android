// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.databinding.ActivitySetupConnectionBinding
import com.tinkernorth.dish.databinding.SetupChoiceRowBinding
import com.tinkernorth.dish.databinding.SetupHostRowBinding
import com.tinkernorth.dish.source.connection.PairingApproval
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.connections.PairPinDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 3 destination. 3A picks the path (Satellite stays here, Bluetooth host
// branches off); 3B lists/scans/reconnects satellites; 3C reuses PairPinDialog
// for PIN entry + reverse approval. On a satellite reaching Connected/Unstable
// the flow hands off to the configure step with the host id as the connection id.
@AndroidEntryPoint
class SetupConnectionActivity : BaseGamepadHostActivity() {
    @Inject lateinit var onboarding: OnboardingPreferenceStore

    private lateinit var binding: ActivitySetupConnectionBinding
    private val viewModel: SetupConnectionViewModel by viewModels()
    private val nav by lazy { DishNavigator(this) }

    private val inputType: String by lazy {
        intent.getStringExtra(SetupFlow.EXTRA_INPUT_TYPE) ?: SetupFlow.INPUT_ONSCREEN
    }
    private val slotId: String by lazy { intent.getStringExtra(SetupFlow.EXTRA_SLOT_ID).orEmpty() }

    // Reused PIN dialog state, mirroring ConnectionsActivity: the manager drives
    // setBusy/setAwaitingApproval/showError around the in-flight pair call, and
    // a host reaching Connected dismisses it via the Connected event.
    private var pinDialog: PairPinDialog? = null
    private var pairingServer: DiscoveredServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySetupConnectionBinding::inflate)
        setupDishToolbar(binding.toolbar)
        wireSetupSkip(binding.toolbar, onboarding)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.breadcrumb.applyStep(SETUP_STEP_DESTINATION)

        bindChoice(
            binding.cardSatellite,
            R.drawable.ic_satellite,
            R.string.setup_conn_satellite_title,
            R.string.setup_conn_satellite_body,
            R.string.setup_conn_satellite_badge,
        ) { viewModel.chooseSatellite() }
        bindChoice(
            binding.cardBluetoothHost,
            R.drawable.ic_bluetooth,
            R.string.setup_conn_bt_host_title,
            R.string.setup_conn_bt_host_body,
            badge = null,
        ) { nav.toSetupBluetoothHost(inputType, slotId) }

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnRescan.setOnClickListener { viewModel.startDiscovery() }
        binding.btnGetSatellite.setOnClickListener { openGitHub() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        observe()
    }

    override fun onDestroy() {
        pinDialog?.setOnDismissListener(null)
        pinDialog?.dismiss()
        pinDialog = null
        super.onDestroy()
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
                        is SetupConnectionViewModel.Event.ShowPairing -> showPairingDialog(event.server)
                        is SetupConnectionViewModel.Event.Connected -> onConnected(event.hostId)
                        is SetupConnectionViewModel.Event.Error -> onConnectionError(event.message)
                    }
                }
            }
        }
    }

    private fun render(state: SetupConnectionViewModel.State) {
        val onSatellite = state.step == SetupConnectionViewModel.Step.SATELLITE
        binding.loader.visibility = if (state.scanning) View.VISIBLE else View.INVISIBLE
        binding.groupPath.visibility = visibleIf(!onSatellite)
        binding.groupSatellite.visibility = visibleIf(onSatellite)
        binding.btnRescan.visibility = visibleIf(onSatellite)
        binding.tvTitle.setText(
            if (onSatellite) R.string.setup_conn_satellite_pick_title else R.string.setup_conn_path_title,
        )
        if (onSatellite) renderHosts(state)
    }

    private fun renderHosts(state: SetupConnectionViewModel.State) {
        binding.tvScanEyebrow.visibility = visibleIf(state.scanning)
        binding.groupGetSatellite.visibility = visibleIf(state.hosts.isEmpty())

        val list = binding.hostList
        list.removeAllViews()
        state.hosts.forEach { host ->
            val row = SetupHostRowBinding.inflate(layoutInflater, list, false)
            row.hostName.text = host.name.ifBlank { getString(R.string.setup_conn_host_unnamed) }
            row.hostStatus.setText(statusFor(host.link))
            row.hostCard.setOnClickListener { viewModel.onHostTapped(host.id) }
            list.addView(row.root)
        }
    }

    @StringRes
    private fun statusFor(link: LinkState): Int =
        when (link) {
            LinkState.Connecting -> R.string.setup_conn_status_reconnecting
            LinkState.Connected, LinkState.Unstable -> R.string.setup_conn_status_connected
            LinkState.Stale -> R.string.setup_conn_status_needs_pairing
            LinkState.Ready, LinkState.Found, LinkState.Saved -> R.string.setup_conn_status_ready
        }

    private fun onConnected(hostId: String) {
        pinDialog?.dismiss()
        nav.toSetupConfigure(slotId, hostId)
    }

    // 3C: reuse the connections-screen PIN dialog verbatim. Path A is type the
    // satellite's PIN; Path B shows this dish's PIN for the operator to accept,
    // sent immediately on open so no extra tap is needed.
    private fun showPairingDialog(server: DiscoveredServer) {
        pinDialog?.dismiss()
        pairingServer = server
        val clientPin = PairingApproval.generatePin()
        val dialog =
            PairPinDialog(
                this,
                clientPin = clientPin,
                onRequestApproval = {
                    pinDialog?.setAwaitingApproval(true)
                    pinDialog?.showError(null)
                    viewModel.requestApproval(server, clientPin)
                },
            ) { pin ->
                pinDialog?.setBusy(true)
                pinDialog?.showError(null)
                viewModel.pairWithPin(server, pin)
            }.apply {
                dishTitle = getString(R.string.pair_dialog_title)
                dishSubtitle =
                    if (server.name.isNotEmpty()) {
                        getString(R.string.pair_dialog_subtitle_named, server.name)
                    } else {
                        getString(R.string.pair_dialog_subtitle)
                    }
                setOnDismissListener {
                    if (pinDialog === this) {
                        pinDialog = null
                        pairingServer = null
                    }
                }
            }
        pinDialog = dialog
        dialog.show()
        dialog.setAwaitingApproval(true)
        viewModel.requestApproval(server, clientPin)
    }

    // A failure while a pair dialog is open keeps the typed PIN and routes the
    // message through the dialog; otherwise surface the generic error sheet.
    private fun onConnectionError(message: String) {
        val dialog = pinDialog
        if (dialog != null && pairingServer != null) {
            dialog.setBusy(false)
            dialog.setAwaitingApproval(false)
            dialog.showError(message)
            return
        }
        SetupErrorDialog.show(this, message) { viewModel.startDiscovery() }
    }

    private fun openGitHub() {
        startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.url_github).toUri()))
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

    private fun handleBack() {
        if (!viewModel.back()) finish()
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE
}
