// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isEmpty
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.DishProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.databinding.ActivityConfigureBindingsBinding
import com.tinkernorth.dish.databinding.BindingApplyStepBinding
import com.tinkernorth.dish.databinding.BindingValueNoneBinding
import com.tinkernorth.dish.databinding.DialogCardListBinding
import com.tinkernorth.dish.databinding.SetupReviewCardBinding
import com.tinkernorth.dish.databinding.SetupTypeCardBinding
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes
import com.tinkernorth.dish.ui.common.tierPillSpec
import com.tinkernorth.dish.ui.donate.wireDonateButton
import com.tinkernorth.dish.ui.setup.ReviewFlow
import com.tinkernorth.dish.ui.setup.bindCapabilityRows
import com.tinkernorth.dish.ui.setup.bindReviewFlows
import com.tinkernorth.dish.ui.setup.capabilityRows
import com.tinkernorth.dish.ui.setup.destinationGetFlows
import com.tinkernorth.dish.ui.setup.destinationSendFlows
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ConfigureBindingsActivity : BaseGamepadHostActivity() {
    private lateinit var binding: ActivityConfigureBindingsBinding
    private val viewModel: ConfigureBindingsViewModel by viewModels()
    private val nav by lazy { DishNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigureBindingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installGamepadHost(binding.root)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        wireDonateButton()

        val slotId = intent.getStringExtra(EXTRA_SLOT_ID)
        if (slotId == null) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnUnbind.setOnClickListener {
            viewModel.unbind()
            finish()
        }
        binding.btnApply.setOnClickListener { viewModel.apply() }

        viewModel.load(slotId)
        observe()
    }

    // Re-verify on entering the screen: a Moonlight pairing is remembered trust, and the
    // only way to learn the host revoked it is to ask.
    override fun onStart() {
        super.onStart()
        viewModel.refreshMoonlight()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { state ->
                    if (state.loaded) renderContent(state)
                    renderBlocker(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.applyState.collect { renderApplyState(it) }
            }
        }
    }

    private fun renderContent(state: ConfigUiState) {
        val snapshot = state.snapshot ?: return
        binding.tvTitle.text =
            getString(if (snapshot.bound) R.string.binding_activity_title else R.string.binding_activity_title_bind)
        binding.btnApply.text =
            getString(if (snapshot.bound) R.string.binding_activity_apply else R.string.binding_activity_bind)
        binding.btnApply.isEnabled = state.canApply
        binding.btnApply.alpha = if (state.canApply) 1f else DISABLED_ALPHA
        binding.btnUnbind.visibility = if (snapshot.bound) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (state.noHosts) View.GONE else View.VISIBLE

        bindInputSection(state, snapshot)
        bindDestinationSection(state, snapshot)
        val session = state.moonlightSession
        binding.sectionMoonlight.root.visibility = if (session == null) View.GONE else View.VISIBLE
        if (session != null) bindMoonlightSection(state, session)
        binding.sectionBinding.root.visibility = if (state.hostChosen) View.VISIBLE else View.GONE
        if (state.hostChosen) bindBindingSection(state)
    }

    private fun bindMoonlightSection(
        state: ConfigUiState,
        session: MoonlightSessionUi,
    ) {
        binding.sectionMoonlight.bindMoonlightSession(
            session = session,
            hostLabel = state.selectedHost?.label.orEmpty(),
            onPickApp = viewModel::selectMoonlightApp,
        ) { action ->
            if (action == MoonlightAction.SEE_BINDINGS) nav.toConnections() else viewModel.onMoonlightAction(action)
        }
    }

    private fun bindInputSection(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        val s = binding.sectionInput
        s.ivInputIcon.setImageResource(snapshot.link.iconRes())
        s.tvInputName.text = " · ${snapshot.name}"

        val (linkLabel, linkIcon) =
            when (snapshot.link) {
                BindingLink.USB -> getString(R.string.binding_link_usb) to R.drawable.ic_usb
                BindingLink.BLUETOOTH -> getString(R.string.binding_link_bluetooth) to R.drawable.ic_bluetooth
                BindingLink.ONSCREEN -> getString(R.string.binding_link_onscreen) to R.drawable.ic_gamepad_virtual
            }
        s.ivConnIcon.setImageResource(linkIcon)
        s.tvConnText.text = linkLabel

        val guessed = snapshot.link == BindingLink.USB && state.draft?.directOn == true && !snapshot.directVerified
        val showDirect = snapshot.link == BindingLink.USB && snapshot.directCapable
        s.directToggle.visibility = if (showDirect) View.VISIBLE else View.GONE
        if (showDirect) {
            val on = state.draft?.directOn == true
            val accent = getColor(if (guessed) R.color.colorWarning else R.color.colorPrimary)
            val labelColor = if (on) accent else getColor(R.color.colorMuted)
            s.tvDirectLabel.setTextColor(labelColor)
            s.ivDirectBolt.imageTintList = ColorStateList.valueOf(labelColor)
            s.swDirect.setOnCheckedChangeListener(null)
            s.swDirect.isChecked = on
            s.swDirect.setOnCheckedChangeListener { _, isChecked -> viewModel.setDirect(isChecked) }
        }

        s.guessedCallout.visibility = if (guessed) View.VISIBLE else View.GONE

        val fc = s.functionsContainer
        fc.removeAllViews()
        if (snapshot.hasRumble) {
            fc.addView(
                fc.inflateBindingPill(getString(R.string.binding_func_rumble), R.drawable.ic_rumble, PillTone.CAP),
            )
        }
        if (snapshot.hasGyro) fc.addView(fc.inflateBindingPill(getString(R.string.binding_func_gyro), R.drawable.ic_motion, PillTone.CAP))
        if (snapshot.hasTouchpad) {
            fc.addView(
                fc.inflateBindingPill(getString(R.string.binding_func_touchpad), R.drawable.ic_touchpad, PillTone.CAP),
            )
        }
        if (fc.isEmpty()) fc.addView(noneValue(fc))
    }

    private fun bindDestinationSection(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        val d = binding.sectionDestination
        d.ivDestIcon.setImageResource(destinationGlyph(state.selectedHost?.kind))
        d.tvDestLabel.text = getString(R.string.binding_label_destination)

        val noHosts = state.noHosts
        d.hostDropdown.visibility = if (noHosts) View.GONE else View.VISIBLE
        if (!noHosts) {
            val host = state.selectedHost
            d.hostDropdown.text = host?.label ?: getString(R.string.binding_choose_destination)
            d.hostDropdown.setTextColor(getColor(if (host != null) R.color.colorOnSurface else R.color.colorMuted))
            d.hostDropdown.setOnClickListener { showHostMenu() }
        }
        val selectedCompat =
            state.selectedHost
                ?.takeIf { !noHosts }
                ?.let { state.hostCompat[it.id] } ?: DishProtocol.Compat.UNKNOWN
        d.destCompatPill.bindCompat(selectedCompat)
        val plainSatellite = state.hostChosen && !state.isBluetoothHost && !state.isMoonlightHost
        d.legendSatellite.visibility = if (plainSatellite) View.VISIBLE else View.GONE
        d.legendBt.visibility = if (state.hostChosen && state.isBluetoothHost) View.VISIBLE else View.GONE
        d.noHostsGroup.visibility = if (noHosts) View.VISIBLE else View.GONE
        if (noHosts) {
            d.tvNoHostsBody.text = getString(R.string.binding_no_hosts_body, snapshot.name)
            d.btnManage.setOnClickListener { nav.toConnections() }
        }
    }

    private fun bindBindingSection(state: ConfigUiState) {
        val bz = binding.sectionBinding
        bindEmulateRow(state)

        val motionVisible = state.motionAvailable
        bz.motionDivider.visibility = if (motionVisible) View.VISIBLE else View.GONE
        bz.motionRow.visibility = if (motionVisible) View.VISIBLE else View.GONE
        if (motionVisible) {
            bz.swMotion.setOnCheckedChangeListener(null)
            bz.swMotion.isChecked = state.draft?.motionOn == true
            bz.swMotion.setOnCheckedChangeListener { _, isChecked -> viewModel.setMotion(isChecked) }
        }

        // Rumble shows when the path can carry it: the phone vibrates as a fallback for the
        // on-screen pad, a physical pad needs its own motor, and a Bluetooth host has no return path.
        val rumbleVisible = state.capabilities.isAvailable(Feature.RUMBLE)
        bz.rumbleDivider.visibility = if (rumbleVisible) View.VISIBLE else View.GONE
        bz.rumbleRow.visibility = if (rumbleVisible) View.VISIBLE else View.GONE
        if (rumbleVisible) {
            bz.swRumble.setOnCheckedChangeListener(null)
            bz.swRumble.isChecked = state.draft?.rumbleOn == true
            bz.swRumble.setOnCheckedChangeListener { _, isChecked -> viewModel.setRumble(isChecked) }
        }
    }

    // The "Emulate as" type is host-owned. A Bluetooth host shows the static profile pill (unchanged).
    // A satellite host shows a loader until its catalog resolves the type, the dropdown once Ready, or a
    // tap-to-retry affordance if the fetch failed with nothing cached — never a guessed default.
    private fun bindEmulateRow(state: ConfigUiState) {
        val bz = binding.sectionBinding
        if (state.isBluetoothHost) {
            bz.tvEmulateText.text = viewModel.typeLabel(state.draft?.type ?: CONTROLLER_TYPE_XBOX)
            bz.emulatePill.visibility = View.VISIBLE
            bz.emulateLoading.visibility = View.GONE
            bz.emulateDropdown.visibility = View.GONE
            return
        }
        bz.emulatePill.visibility = View.GONE
        bz.emulateLoading.visibility = if (state.typeLoad == TypeLoad.Loading) View.VISIBLE else View.GONE
        bz.emulateDropdown.visibility = if (state.typeLoad == TypeLoad.Loading) View.GONE else View.VISIBLE
        when (state.typeLoad) {
            TypeLoad.Loading -> Unit
            TypeLoad.Ready -> {
                state.draft?.type?.let { bz.emulateDropdown.text = viewModel.typeLabel(it) }
                bz.emulateDropdown.setIconResource(R.drawable.ic_chevron_down)
                bz.emulateDropdown.setOnClickListener { showTypeMenu() }
            }
            TypeLoad.Error -> {
                bz.emulateDropdown.text = getString(R.string.binding_emulate_load_failed)
                bz.emulateDropdown.setIconResource(R.drawable.ic_refresh)
                bz.emulateDropdown.setOnClickListener { viewModel.retryTypeLoad() }
            }
        }
    }

    private fun noneValue(parent: ViewGroup): View = BindingValueNoneBinding.inflate(layoutInflater, parent, false).root

    private fun renderApplyState(state: ApplyState) {
        when (state) {
            is ApplyState.Idle -> {
                binding.applyOverlay.visibility = View.GONE
                binding.toast.visibility = View.GONE
            }
            is ApplyState.Running -> {
                binding.toast.visibility = View.GONE
                binding.applyOverlay.visibility = View.VISIBLE
                renderSteps(state)
            }
            is ApplyState.Finished -> {
                binding.applyOverlay.visibility = View.GONE
                if (state.errorMessage != null) renderErrorToast(state) else finishWithToast(state)
            }
        }
    }

    private fun renderSteps(state: ApplyState.Running) {
        binding.applySteps.removeAllViews()
        state.steps.forEachIndexed { i, step ->
            val stepB = BindingApplyStepBinding.inflate(layoutInflater, binding.applySteps, false)
            when {
                i < state.doneCount -> stepB.ivStepDone.visibility = View.VISIBLE
                i == state.doneCount -> stepB.pbStepActive.visibility = View.VISIBLE
                else -> stepB.vStepPending.visibility = View.VISIBLE
            }
            stepB.tvStepLabel.text = step.label
            stepB.root.alpha = if (i > state.doneCount) 0.5f else 1f
            binding.applySteps.addView(stepB.root)
        }
    }

    private fun finishWithToast(state: ApplyState.Finished) {
        val warning = state.warningMessage
        if (warning != null) {
            notifications.postDeferred(
                severity = DishNotification.Severity.WARN,
                title = getString(R.string.binding_apply_warn_title),
                body = warning,
            )
        } else {
            notifications.postDeferred(
                severity = DishNotification.Severity.SUCCESS,
                title = getString(R.string.binding_apply_success_title),
                body = getString(R.string.binding_apply_success_detail, state.controllerName, state.hostName),
            )
        }
        finish()
    }

    private fun renderBlocker(state: ConfigUiState) {
        val blocker = state.blocker
        binding.blockerOverlay.visibility = if (blocker == null) View.GONE else View.VISIBLE
        if (blocker == null) return
        binding.btnBlockerCancel.setOnClickListener { finish() }
        when (blocker) {
            is BindingBlocker.InputLost -> {
                val icon = if (state.snapshot?.link == BindingLink.BLUETOOTH) R.drawable.ic_bluetooth else R.drawable.ic_usb
                bindBlockerMessage(
                    icon,
                    R.color.colorWarning,
                    R.string.binding_edge_input_lost_title,
                    getString(R.string.binding_blocker_input_lost_body),
                )
                bindBlockerPrimary(null, busy = false) {}
            }
            is BindingBlocker.HostLost -> {
                val label = blocker.hostLabel.ifBlank { getString(R.string.satellite_fallback_name) }
                bindBlockerMessage(
                    R.drawable.ic_error,
                    R.color.colorError,
                    R.string.binding_edge_host_lost_title,
                    getString(R.string.binding_edge_host_lost_detail, label),
                )
                bindBlockerPrimary(R.string.binding_edge_action_reconnect, blocker.reconnecting) { viewModel.reconnectHosts() }
            }
            is BindingBlocker.HostUnsteady -> {
                bindBlockerMessage(
                    R.drawable.ic_warning,
                    R.color.colorWarning,
                    R.string.binding_edge_unsteady_title,
                    getString(R.string.binding_edge_unsteady_detail),
                )
                bindBlockerPrimary(R.string.binding_edge_action_dismiss, busy = false) { viewModel.dismissUnsteady() }
            }
        }
    }

    private fun bindBlockerMessage(
        @DrawableRes icon: Int,
        @ColorRes tint: Int,
        @StringRes title: Int,
        body: String,
    ) {
        binding.ivBlockerIcon.setImageResource(icon)
        binding.ivBlockerIcon.imageTintList = ColorStateList.valueOf(getColor(tint))
        binding.tvBlockerTitle.setText(title)
        binding.tvBlockerBody.text = body
    }

    private fun bindBlockerPrimary(
        @StringRes label: Int?,
        busy: Boolean,
        onClick: () -> Unit,
    ) {
        binding.blockerPrimarySlot.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.btnBlockerPrimary.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        binding.pbBlockerBusy.visibility = if (busy) View.VISIBLE else View.GONE
        if (label != null) {
            binding.btnBlockerPrimary.setText(label)
            binding.btnBlockerPrimary.setOnClickListener { onClick() }
        }
    }

    private fun renderErrorToast(state: ApplyState.Finished) {
        binding.toast.visibility = View.VISIBLE
        binding.ivToastIcon.setImageResource(R.drawable.ic_error)
        binding.ivToastIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.colorError))
        binding.tvToastTitle.text = getString(R.string.binding_apply_error_title)
        binding.tvToastDetail.visibility = View.VISIBLE
        binding.tvToastDetail.text = state.errorMessage
        binding.btnToastAction.text = getString(R.string.binding_apply_action_retry)
        binding.btnToastAction.setOnClickListener { viewModel.apply() }
    }

    // The destination picker shows each host as the setup flow's destination card: the
    // host, its transport, and every flow it COULD get and send at its best emulated
    // type. The current input/type picks do not gate this card (that narrowing belongs
    // to the type picker and the review); the picker's job is comparing hosts.
    private fun showHostMenu() {
        val state = viewModel.ui.value
        val snapshot = state.snapshot ?: return
        val list = DialogCardListBinding.inflate(layoutInflater)
        val container = list.dialogCardList
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.binding_label_destination)
                .setView(list.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        state.hosts.forEach { host ->
            val potential = viewModel.destinationPotential(snapshot.slotId, host.kind, host.id)
            val card = SetupReviewCardBinding.inflate(layoutInflater, container, false)
            card.reviewIcon.setImageResource(destinationGlyph(host.kind))
            card.reviewKind.setText(R.string.binding_label_destination)
            card.reviewName.text = host.label
            card.reviewSublabel.text = destinationSublabel(host)
            card.reviewTierPill.bindPill(tierPillSpec(host.kind))
            card.reviewTierPill.root.visibility = View.VISIBLE
            card.reviewCompatPill.bindCompat(state.hostCompat[host.id] ?: DishProtocol.Compat.UNKNOWN)
            bindReviewFlows(card.reviewSendsRow, card.reviewSendsChips, destinationSends(potential))
            bindReviewFlows(card.reviewGetsRow, card.reviewGetsChips, destinationGets(potential))
            card.reviewCard.isClickable = true
            card.reviewCard.setOnClickListener {
                viewModel.setHost(host.id)
                dialog.dismiss()
            }
            container.addView(card.root)
        }
        dialog.show()
    }

    // One silhouette per destination kind, everywhere the destination is drawn.
    @DrawableRes
    private fun destinationGlyph(kind: ConnectionKind?): Int =
        when (kind) {
            ConnectionKind.BLUETOOTH -> R.drawable.ic_bluetooth
            ConnectionKind.MOONLIGHT -> R.drawable.ic_pc_monitor
            else -> R.drawable.ic_satellite
        }

    private fun destinationSublabel(host: BindingHost): String =
        when (host.kind) {
            ConnectionKind.BLUETOOTH -> getString(R.string.setup_cfg_dest_bluetooth)
            ConnectionKind.MOONLIGHT -> getString(R.string.ml_dest_sublabel, viewModel.moonlightAddress(host.id))
            ConnectionKind.SATELLITE -> getString(R.string.setup_cfg_dest_satellite)
        }

    // A destination gets every input some emulated type can land on it, and sends
    // back every feedback surface some type can source (shared with the review).
    private fun destinationGets(potential: CapabilitySet): List<ReviewFlow> = destinationGetFlows(potential)

    private fun destinationSends(potential: CapabilitySet): List<ReviewFlow> = destinationSendFlows(potential)

    // The type picker shows each emulated type with the setup flow's capability table
    // (what each carries per feature, and whether it is available) so the choice is
    // informed rather than a bare label.
    private fun showTypeMenu() {
        val state = viewModel.ui.value
        val snapshot = state.snapshot ?: return
        val host = state.selectedHost ?: return
        val list = DialogCardListBinding.inflate(layoutInflater)
        val container = list.dialogCardList
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.binding_label_emulate)
                .setView(list.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        val moonlight = host.kind == ConnectionKind.MOONLIGHT
        state.typeOptions.forEach { option ->
            val card = SetupTypeCardBinding.inflate(layoutInflater, container, false)
            val candidate = if (moonlight) viewModel.moonlightResolvedType(option.id) else option.id
            card.typeTitle.text = option.label
            card.typeChevron.visibility = View.GONE
            card.typeCard.isChecked = option.id == state.draft?.type
            // Auto is resolved here, on the client: the card shows the rows of the type it
            // will actually send, and says which one that is rather than implying a fifth type.
            val isAuto = moonlight && option.id == MoonlightEmulatedType.AUTO
            card.typeBadge.visibility = if (isAuto) View.VISIBLE else View.GONE
            if (isAuto) card.typeBadge.setText(R.string.ml_type_auto_badge)
            card.typeCaption.visibility = if (isAuto) View.VISIBLE else View.GONE
            if (isAuto) {
                card.typeCaption.text =
                    getString(R.string.ml_type_auto_resolved, getString(moonlightTypeLabelRes(candidate)))
            }
            card.capabilityContainer.bindCapabilityRows(
                capabilityRows(viewModel.capabilityForCandidate(snapshot.slotId, candidate, host.kind, host.id)),
            )
            card.typeCard.setOnClickListener {
                viewModel.setType(option.id)
                dialog.dismiss()
            }
            container.addView(card.root)
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_SLOT_ID = "extra_slot_id"

        private const val DISABLED_ALPHA = 0.4f
    }
}
