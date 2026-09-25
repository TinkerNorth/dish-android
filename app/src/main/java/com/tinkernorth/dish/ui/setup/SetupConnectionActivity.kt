// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.LinkTier
import com.tinkernorth.dish.composer.linkTierFor
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.databinding.ActivitySetupConnectionBinding
import com.tinkernorth.dish.databinding.SetupChoiceRowBinding
import com.tinkernorth.dish.databinding.SetupHostRowBinding
import com.tinkernorth.dish.source.connection.generatePin
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.source.system.PERMISSION
import com.tinkernorth.dish.source.system.isGranted
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.paintTierBadge
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.connections.PairPinDialog
import com.tinkernorth.dish.ui.main.bindCompat
import com.tinkernorth.dish.ui.main.chipTextRes
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
        intent.getStringExtra(EXTRA_INPUT_TYPE) ?: INPUT_ONSCREEN
    }
    private val slotId: String by lazy { intent.getStringExtra(EXTRA_SLOT_ID).orEmpty() }

    // Reused PIN dialog state, mirroring ConnectionsActivity: the manager drives
    // setBusy/setAwaitingApproval/showError around the in-flight pair call, and
    // a host reaching Connected dismisses it via the Connected event.
    private val pairing = SetupPairing()

    private var onLocalNetworkGranted: (() -> Unit)? = null

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val resume = onLocalNetworkGranted
            onLocalNetworkGranted = null
            if (granted) {
                resume?.invoke()
            } else {
                show(this, getString(R.string.setup_conn_local_network_denied)) {
                    withLocalNetwork(resume ?: { viewModel.startDiscovery() })
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySetupConnectionBinding::inflate)
        setupDishToolbar(binding.toolbar)
        wireSetupSkip(binding.toolbar, onboarding)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.breadcrumb.applyStep(SETUP_STEP_DESTINATION)

        bindDestinationChoices()
        wireFooterButtons()
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        observe()
    }

    // Ordered best link first, which is the same order the connections screen lists them in.
    private fun bindDestinationChoices() {
        bindChoice(
            binding.cardSatellite,
            R.drawable.ic_satellite,
            R.string.setup_conn_satellite_title,
            R.string.setup_conn_satellite_body,
            linkTierFor(ConnectionKind.SATELLITE),
        ) { withLocalNetwork { viewModel.chooseSatellite() } }
        bindChoice(
            binding.cardMoonlight,
            R.drawable.ic_pc_monitor,
            R.string.ml_dest_section,
            R.string.setup_conn_moonlight_body,
            linkTierFor(ConnectionKind.MOONLIGHT),
        ) { withLocalNetwork { viewModel.chooseMoonlight() } }
        bindChoice(
            binding.cardBluetoothHost,
            R.drawable.ic_bluetooth,
            R.string.setup_conn_bt_host_title,
            R.string.setup_conn_bt_host_body,
            linkTierFor(ConnectionKind.BLUETOOTH),
        ) { nav.toSetupBluetoothHost(inputType, slotId) }
    }

    private fun wireFooterButtons() {
        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnRescan.setOnClickListener { withLocalNetwork { rescan() } }
        binding.btnGetSatellite.setOnClickListener { openGitHub() }
    }

    override fun onDestroy() {
        pairing.close()
        super.onDestroy()
    }

    private fun observe() {
        observeWhileStarted(viewModel.state) { render(it) }
        observeWhileStarted(viewModel.events) { event ->
            when (event) {
                is SetupConnectionViewModel.Event.ShowPairing -> pairing.show(event.server)
                is SetupConnectionViewModel.Event.Connected -> onConnected(event.hostId)
                is SetupConnectionViewModel.Event.Error -> onConnectionError(event.message)
            }
        }
    }

    private fun render(state: SetupConnectionViewModel.State) {
        val onSatellite = state.step == SetupConnectionViewModel.Step.SATELLITE
        val onMoonlight = state.step == SetupConnectionViewModel.Step.MOONLIGHT
        val scanning = if (onMoonlight) state.moonlightScanning else state.scanning
        binding.loader.visibility = if (scanning) View.VISIBLE else View.INVISIBLE
        binding.groupPath.visibility = visibleIf(!onSatellite && !onMoonlight)
        binding.groupSatellite.visibility = visibleIf(onSatellite)
        binding.groupMoonlight.visibility = visibleIf(onMoonlight)
        binding.btnRescan.visibility = visibleIf(onSatellite || onMoonlight)
        binding.tvTitle.setText(
            when {
                onSatellite -> R.string.setup_conn_satellite_pick_title
                onMoonlight -> R.string.ml_dest_section
                else -> R.string.setup_conn_path_title
            },
        )
        if (onSatellite) renderHosts(state)
        if (onMoonlight) renderMoonlightHosts(state)
    }

    private fun rescan() {
        if (viewModel.state.value.step == SetupConnectionViewModel.Step.MOONLIGHT) {
            viewModel.startMoonlightDiscovery()
        } else {
            viewModel.startDiscovery()
        }
    }

    // The row says which of the three trust words applies and never a live link: a
    // Moonlight host has no way to tell us it is up, and the binding is what starts a session.
    private fun renderMoonlightHosts(state: SetupConnectionViewModel.State) {
        binding.tvMoonlightEyebrow.visibility = visibleIf(state.moonlightScanning)
        binding.groupNoMoonlight.visibility = visibleIf(state.moonlightHosts.isEmpty())

        val list = binding.moonlightList
        list.removeAllViews()
        state.moonlightHosts.forEach { host ->
            val row = SetupHostRowBinding.inflate(layoutInflater, list, false)
            row.hostName.text = host.name.ifBlank { getString(R.string.setup_conn_host_unnamed) }
            row.hostStatus.setText(host.trust.chipTextRes())
            row.hostCard.setOnClickListener { viewModel.onMoonlightHostTapped(host.id) }
            list.addView(row.root)
        }
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
            row.hostUpdatePill.bindCompat(host.compat)
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
        pairing.dismiss()
        nav.toSetupConfigure(slotId, hostId)
    }

    // 3C: reuse the connections-screen PIN dialog verbatim. Path A is type the
    // satellite's PIN; Path B shows this dish's PIN for the operator to accept,
    // sent immediately on open so no extra tap is needed.
    // The PIN exchange: one dialog at a time and the server it belongs to, kept together so the
    // Activity does not carry either. Path A is typing the satellite's PIN; path B shows this
    // dish's PIN for the operator to accept.
    private inner class SetupPairing {
        private var dialog: PairPinDialog? = null
        private var server: DiscoveredServer? = null

        fun show(target: DiscoveredServer) {
            dialog?.dismiss()
            server = target
            val clientPin = generatePin()
            val built = build(target, clientPin)
            dialog = built
            built.show()
            built.setAwaitingApproval(true)
            viewModel.requestApproval(target, clientPin)
        }

        fun dismiss() {
            dialog?.dismiss()
        }

        fun close() {
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            dialog = null
        }

        /** Answers whether a pairing was in flight to take the error, keeping the typed PIN. */
        fun showError(message: String): Boolean {
            val live = dialog ?: return false
            if (server == null) return false
            live.setBusy(false)
            live.setAwaitingApproval(false)
            live.showError(message)
            return true
        }

        private fun build(
            target: DiscoveredServer,
            clientPin: String,
        ): PairPinDialog =
            PairPinDialog(
                this@SetupConnectionActivity,
                clientPin = clientPin,
                onRequestApproval = { requestApprovalAgain(target, clientPin) },
            ) { pin -> submitServerPin(target, pin) }
                .apply {
                    dishTitle = getString(R.string.pair_dialog_title)
                    dishSubtitle = subtitleFor(target)
                    setOnDismissListener { forget(this) }
                }

        private fun requestApprovalAgain(
            target: DiscoveredServer,
            clientPin: String,
        ) {
            dialog?.setAwaitingApproval(true)
            dialog?.showError(null)
            viewModel.requestApproval(target, clientPin)
        }

        private fun submitServerPin(
            target: DiscoveredServer,
            pin: String,
        ) {
            dialog?.setBusy(true)
            dialog?.showError(null)
            viewModel.pairWithPin(target, pin)
        }

        private fun subtitleFor(target: DiscoveredServer): String {
            val isNamed = target.name.isNotEmpty()
            if (isNamed) return getString(R.string.pair_dialog_subtitle_named, target.name)
            return getString(R.string.pair_dialog_subtitle)
        }

        // Only the dialog still on screen may clear the fields; a late dismiss from a replaced one
        // must not wipe the new attempt.
        private fun forget(dismissed: PairPinDialog) {
            if (dialog !== dismissed) return
            dialog = null
            server = null
        }
    }

    // A failure while a pair dialog is open keeps the typed PIN and routes the
    // message through the dialog; otherwise surface the generic error sheet.
    private fun onConnectionError(message: String) {
        if (pairing.showError(message)) return
        show(this, message) { withLocalNetwork { viewModel.startDiscovery() } }
    }

    private fun openGitHub() {
        openExternalUrl(getString(R.string.url_github))
    }

    // Request before scanning: a pre-grant blocked scan would shadow the real one via the single-flight guard.
    private fun withLocalNetwork(action: () -> Unit) {
        if (isGranted(this)) {
            action()
            return
        }
        onLocalNetworkGranted = action
        localNetworkPermissionLauncher.launch(PERMISSION)
    }

    private fun bindChoice(
        row: SetupChoiceRowBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes body: Int,
        tier: LinkTier,
        onClick: () -> Unit,
    ) {
        row.choiceIcon.setImageResource(icon)
        row.choiceTitle.setText(title)
        row.choiceBody.setText(body)
        row.choiceBadge.visibility = View.VISIBLE
        row.choiceBadge.paintTierBadge(tier)
        row.choiceCard.setOnClickListener { onClick() }
    }

    private fun handleBack() {
        if (!viewModel.back()) finish()
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE
}
