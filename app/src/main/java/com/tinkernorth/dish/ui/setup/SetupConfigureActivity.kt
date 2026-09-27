// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.databinding.ActivitySetupConfigureBinding
import com.tinkernorth.dish.databinding.SetupReviewCardBinding
import com.tinkernorth.dish.databinding.SetupTypeCardBinding
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.bundledControllerTypeGlyphRes
import com.tinkernorth.dish.ui.common.moonlightTypeGlyphRes
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.main.ApplyState
import com.tinkernorth.dish.ui.main.BindingLink
import com.tinkernorth.dish.ui.main.BindingSnapshot
import com.tinkernorth.dish.ui.main.ConfigUiState
import com.tinkernorth.dish.ui.main.ConfigureBindingsViewModel
import com.tinkernorth.dish.ui.main.MoonlightAction
import com.tinkernorth.dish.ui.main.StringLookup
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import com.tinkernorth.dish.ui.main.bindCompat
import com.tinkernorth.dish.ui.main.bindMoonlightSession
import com.tinkernorth.dish.ui.main.iconRes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Stage 4 of the guided flow: type + capability table (4A), feel (4B), review &
// bind (4C). Reuses ConfigureBindingsViewModel for gating, the USB-direct apply,
// and the bind round-trip; this screen only renders sub-steps and feeds the
// wizard's chosen destination in via setHost.
@AndroidEntryPoint
class SetupConfigureActivity : BaseGamepadHostActivity() {
    @Inject lateinit var onboarding: OnboardingPreferenceStore

    private lateinit var binding: ActivitySetupConfigureBinding
    private val nav by lazy { DishNavigator(this) }
    private val viewModel: ConfigureBindingsViewModel by viewModels()
    private val strings = ContextStrings(this)

    private var step = Step.TYPE
    private var current = ConfigUiState()

    // ApplyState.Finished is a retained StateFlow value, so a STOP/START cycle re-collects it;
    // guard the dashboard handoff so it fires once.
    private var finished = false

    // The slot the screen actually loaded (a USB Direct claim can retire the id from the prior
    // step); the type cards resolve their candidate capabilities against this same id.
    private var resolvedSlotId = VIRTUAL_SLOT_ID

    // SESSION only exists for a Moonlight destination: it is where the app that host will
    // run is settled, which is a different question from how the pad feels.
    private enum class Step { TYPE, SESSION, FEEL, REVIEW }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = setScaffoldContent(ActivitySetupConfigureBinding::inflate)
        setupDishToolbar(binding.toolbar)
        wireSetupSkip(binding.toolbar, onboarding)
        binding.breadcrumb.applyStep(SETUP_STEP_BINDING)

        val slotId = intent.getStringExtra(EXTRA_SLOT_ID)
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        if (slotId == null || connectionId == null) {
            finish()
            return
        }

        resolvedSlotId = resolveSlotId(slotId)
        viewModel.load(resolvedSlotId)
        viewModel.setHost(connectionId)
        wire()
        observe()
    }

    private fun wire() {
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnContinue.setOnClickListener { advance() }

        binding.cardTypeXbox.typeCard.setOnClickListener { pickType(CONTROLLER_TYPE_XBOX) }
        binding.cardTypePlaystation.typeCard.setOnClickListener { pickType(CONTROLLER_TYPE_PLAYSTATION) }
        binding.cardTypeDualsense.typeCard.setOnClickListener { pickType(CONTROLLER_TYPE_DUALSENSE) }
        binding.cardTypeSwitchpro.typeCard.setOnClickListener { pickType(CONTROLLER_TYPE_SWITCHPRO) }

        binding.cardMlAuto.typeCard.setOnClickListener { pickType(AUTO) }
        binding.cardMlXbox.typeCard.setOnClickListener { pickType(XBOX) }
        binding.cardMlPlaystation.typeCard.setOnClickListener { pickType(PLAYSTATION) }
        binding.cardMlNintendo.typeCard.setOnClickListener { pickType(NINTENDO) }
    }

    private fun observe() {
        observeWhileStarted(viewModel.ui) { onUiState(it) }
        observeWhileStarted(viewModel.applyState) { renderApplyState(it) }
    }

    private fun onUiState(state: ConfigUiState) {
        current = state
        if (state.loaded) render(state)
    }

    private fun render(state: ConfigUiState) {
        val snapshot = state.snapshot ?: return
        binding.groupType.visibility = visibleIf(step == Step.TYPE)
        binding.groupMoonlightSession.root.visibility = visibleIf(step == Step.SESSION)
        binding.groupFeel.visibility = visibleIf(step == Step.FEEL)
        binding.groupReview.visibility = visibleIf(step == Step.REVIEW)
        when (step) {
            Step.TYPE -> renderType(state)
            Step.SESSION -> renderSession(state)
            Step.FEEL -> renderFeel(state)
            Step.REVIEW -> renderReview(state, snapshot)
        }
    }

    // 4A. Each card carries the capability table computed for THAT candidate type
    // so the trade-off (PlayStation unlocks motion/touchpad) is visible before the
    // pick. A Bluetooth host has its type fixed upstream, so only the chosen one
    // shows and the cards stop being tappable.
    private fun renderType(state: ConfigUiState) {
        renderTypeHeadings(state)
        renderPadTypeCards(state)
        renderMoonlightTypeCards(state)
    }

    private fun renderTypeHeadings(state: ConfigUiState) {
        val moonlight = state.isMoonlightHost
        binding.tvTitle.setText(if (moonlight) R.string.ml_type_title else R.string.setup_cfg_type_title)
        binding.tvSubtitle.text = typeSubtitleFor(state)
        binding.btnContinue.setText(R.string.setup_cfg_continue)
        // Tapping a type commits and advances; only the locked Bluetooth-host case, where the
        // cards aren't tappable, needs the Next button.
        binding.btnContinue.visibility = visibleIf(state.isBluetoothHost)
    }

    private fun typeSubtitleFor(state: ConfigUiState): String =
        getString(typeSubtitleRes(state.selectedHost?.kind), state.selectedHost?.label.orEmpty())

    private fun padTypeCards() =
        listOf(
            binding.cardTypeXbox to CONTROLLER_TYPE_XBOX,
            binding.cardTypePlaystation to CONTROLLER_TYPE_PLAYSTATION,
            binding.cardTypeDualsense to CONTROLLER_TYPE_DUALSENSE,
            binding.cardTypeSwitchpro to CONTROLLER_TYPE_SWITCHPRO,
        )

    // A Bluetooth host locks to one type, so its other cards never show, and a Moonlight host
    // uses the separate set below instead of these.
    private fun renderPadTypeCards(state: ConfigUiState) {
        val moonlight = state.isMoonlightHost
        val locked = state.isBluetoothHost
        val selectedType = state.draft?.type ?: CONTROLLER_TYPE_XBOX
        for ((card, type) in padTypeCards()) {
            bindTypeCard(card, state, type, locked)
            card.typeCard.visibility = visibleIf(!moonlight && (!locked || selectedType == type))
        }
    }

    private fun renderMoonlightTypeCards(state: ConfigUiState) {
        val moonlight = state.isMoonlightHost
        bindMoonlightTypeCard(binding.cardMlAuto, state, AUTO, moonlight)
        bindMoonlightTypeCard(binding.cardMlXbox, state, XBOX, moonlight)
        bindMoonlightTypeCard(binding.cardMlPlaystation, state, PLAYSTATION, moonlight)
        bindMoonlightTypeCard(binding.cardMlNintendo, state, NINTENDO, moonlight)
    }

    private fun bindTypeCard(
        card: SetupTypeCardBinding,
        state: ConfigUiState,
        candidateType: Int,
        locked: Boolean,
    ) {
        card.typeTitle.text = viewModel.typeLabel(candidateType)
        // A Bluetooth host's Xbox is the generic pad the phone advertises, not the
        // satellite's emulated Xbox 360, so its card wears the modern silhouette.
        card.typeGlyph.setImageResource(
            if (state.isBluetoothHost && candidateType == CONTROLLER_TYPE_XBOX) {
                R.drawable.ic_ctrl_xbox
            } else {
                bundledControllerTypeGlyphRes(candidateType)
            },
        )
        card.typeChevron.visibility = visibleIf(!locked)
        card.typeCard.isClickable = !locked
        card.typeCard.isChecked = state.draft?.type == candidateType
        card.capabilityContainer.bindCapabilityRows(
            capabilityRows(
                viewModel.capabilityForCandidate(
                    slotId = resolvedSlotId,
                    candidateType = candidateType,
                    candidateHostKind = state.selectedHost?.kind ?: ConnectionKind.SATELLITE,
                    candidateHostId = state.draft?.hostId,
                ),
                inputUnknown = state.inputUnknown,
            ),
        )
    }

    // The Moonlight type table is the host emulator's, not the satellite catalog's, and
    // Auto resolves here on the client so its card can show the rows it will really send.
    private fun bindMoonlightTypeCard(
        card: SetupTypeCardBinding,
        state: ConfigUiState,
        candidateType: Int,
        visible: Boolean,
    ) {
        card.typeCard.visibility = visibleIf(visible)
        if (!visible) return
        val resolved = viewModel.moonlightResolvedType(candidateType)
        card.typeTitle.setText(moonlightTypeLabelRes(candidateType))
        card.typeGlyph.setImageResource(moonlightTypeGlyphRes(candidateType))
        card.typeChevron.visibility = View.GONE
        card.typeCard.isClickable = true
        card.typeCard.isChecked = state.draft?.type == candidateType
        val auto = candidateType == AUTO
        card.typeBadge.visibility = visibleIf(auto)
        card.typeCaption.visibility = visibleIf(auto)
        if (auto) {
            card.typeBadge.setText(R.string.ml_type_auto_badge)
            card.typeCaption.text =
                getString(R.string.ml_type_auto_resolved, getString(moonlightTypeLabelRes(resolved)))
        }
        card.capabilityContainer.bindCapabilityRows(
            capabilityRows(
                viewModel.capabilityForCandidate(
                    slotId = resolvedSlotId,
                    candidateType = resolved,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = state.draft?.hostId,
                ),
                inputUnknown = state.inputUnknown,
            ),
        )
    }

    // The app the host will run is settled once per session, so this step is the same
    // section the binding screen draws and it never blocks the wizard: with nothing
    // picked the session starts whatever the host lists first.
    private fun renderSession(state: ConfigUiState) {
        binding.tvTitle.setText(R.string.setup_cfg_session_title)
        binding.tvSubtitle.setText(R.string.setup_cfg_session_subtitle)
        binding.btnContinue.setText(R.string.setup_cfg_continue)
        binding.btnContinue.visibility = View.VISIBLE
        val session = state.moonlightSession ?: return
        binding.groupMoonlightSession.bindMoonlightSession(
            session = session,
            hostLabel = state.selectedHost?.label.orEmpty(),
            onPickApp = viewModel::selectMoonlightApp,
        ) { action ->
            if (action == MoonlightAction.SEE_BINDINGS) nav.toConnections() else viewModel.onMoonlightAction(action)
        }
    }

    // 4B mirrors ConfigureBindingsActivity.bindBindingSection gating exactly: only
    // the rows the current input/destination/type combination supports appear, and
    // listeners are nulled before state is written so re-render never echoes back.
    private fun renderFeel(state: ConfigUiState) {
        binding.tvTitle.setText(R.string.setup_cfg_feel_title)
        binding.tvSubtitle.setText(R.string.setup_cfg_feel_subtitle)
        binding.btnContinue.setText(R.string.setup_cfg_continue)
        binding.btnContinue.visibility = View.VISIBLE

        val motionVisible = state.motionAvailable
        // Rumble shows when the path can carry it: a Satellite host returns it, the phone
        // vibrates as a fallback for the on-screen pad, and a physical pad needs its own motor.
        val rumbleVisible = state.capabilities.isAvailable(Feature.RUMBLE)
        renderMotionRow(state, motionVisible)
        renderRumbleRow(state, rumbleVisible, motionVisible)
        binding.tvFeelEmpty.visibility = visibleIf(!motionVisible && !rumbleVisible)
    }

    // The listener is cleared before the checked state is set: setChecked fires it, and a render
    // must not read as the user having flipped the switch.
    private fun renderMotionRow(
        state: ConfigUiState,
        visible: Boolean,
    ) {
        binding.motionRow.visibility = visibleIf(visible)
        if (!visible) return
        binding.swMotion.setOnCheckedChangeListener(null)
        binding.swMotion.isChecked = state.draft?.motionOn == true
        binding.swMotion.setOnCheckedChangeListener { _, isChecked -> viewModel.setMotion(isChecked) }
    }

    // The divider only earns its space between two visible rows.
    private fun renderRumbleRow(
        state: ConfigUiState,
        visible: Boolean,
        motionVisible: Boolean,
    ) {
        binding.rumbleDivider.visibility = visibleIf(visible && motionVisible)
        binding.rumbleRow.visibility = visibleIf(visible)
        if (!visible) return
        binding.swRumble.setOnCheckedChangeListener(null)
        binding.swRumble.isChecked = state.draft?.rumbleOn == true
        binding.swRumble.setOnCheckedChangeListener { _, isChecked -> viewModel.setRumble(isChecked) }
    }

    // 4C: one card per source and destination, each showing what it sends (up)
    // and gets (down) so the whole data flow is visible before binding.
    private fun renderReview(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        binding.tvTitle.setText(R.string.setup_cfg_review_title)
        binding.tvSubtitle.setText(R.string.setup_cfg_review_subtitle)
        binding.btnContinue.setText(R.string.setup_cfg_bind)
        binding.btnContinue.visibility = View.VISIBLE

        val container = binding.reviewContainer
        container.removeAllViews()
        val nodes = reviewGraph(reviewInputFor(state, snapshot), reviewModelFor(state), strings)
        nodes.forEach { node ->
            val card = SetupReviewCardBinding.inflate(layoutInflater, container, false)
            card.reviewIcon.setImageResource(node.icon)
            card.reviewKind.setText(node.kind)
            card.reviewName.text = node.name
            card.reviewSublabel.text = node.sublabel
            card.reviewCompatPill.bindCompat(node.compat)
            bindReviewFlows(card.reviewSendsRow, card.reviewSendsChips, node.sends)
            bindReviewFlows(card.reviewGetsRow, card.reviewGetsChips, node.gets)
            container.addView(card.root)
        }
    }

    private fun reviewModelFor(state: ConfigUiState): ReviewModel =
        reviewModelFor(
            caps = state.capabilities,
            motionOn = state.draft?.motionOn == true,
            rumbleOn = state.draft?.rumbleOn == true,
            micOn = state.draft?.micOn == true,
            speakerOn = state.draft?.speakerOn == true,
        )

    private fun reviewInputFor(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ): ReviewInput =
        ReviewInput(
            onScreenInput = snapshot.link == BindingLink.ONSCREEN,
            inputName = snapshot.name,
            inputIcon = snapshot.link.iconRes(),
            inputLinkLabel = inputLinkLabel(state, snapshot),
            inputUnknown = state.inputUnknown,
            hostKind = state.selectedHost?.kind ?: ConnectionKind.SATELLITE,
            hostLabel = state.selectedHost?.label.orEmpty(),
            hostCompat = state.draft?.hostId?.let { state.hostCompat[it] } ?: DishProtocolCompat.UNKNOWN,
            padTypeLabel = padTypeLabelFor(state),
            moonlightAddress = viewModel.moonlightAddress(state.draft?.hostId.orEmpty()),
        )

    // The Moonlight type table is the host emulator's, resolved here so Auto names the type it
    // will really send; every other host reads the satellite catalog's label.
    private fun padTypeLabelFor(state: ConfigUiState): String =
        if (state.isMoonlightHost) {
            getString(moonlightTypeLabelRes(viewModel.moonlightResolvedType(state.draft?.type ?: AUTO)))
        } else {
            viewModel.typeLabel(state.draft?.type ?: CONTROLLER_TYPE_XBOX)
        }

    private fun renderApplyState(state: ApplyState) {
        when (state) {
            is ApplyState.Idle -> setBindBusy(false)
            is ApplyState.Running -> setBindBusy(true)
            is ApplyState.Finished -> {
                setBindBusy(false)
                if (state.errorMessage != null) {
                    show(this, state.errorMessage) { viewModel.apply() }
                } else {
                    finishToDashboard(state)
                }
            }
        }
    }

    private fun setBindBusy(busy: Boolean) {
        binding.loader.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        binding.btnBindProgress.visibility = visibleIf(busy && step == Step.REVIEW)
        binding.btnContinue.isEnabled = !busy
        binding.btnBack.isEnabled = !busy
    }

    // Mirrors ConfigureBindingsActivity.finishWithToast: the result is held for the
    // next screen (the dashboard) since a live post would die with this activity.
    private fun finishToDashboard(state: ApplyState.Finished) {
        if (finished) return
        finished = true
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
                title = getString(R.string.setup_cfg_done_title),
                body = getString(R.string.setup_cfg_done_body, state.controllerName, state.hostName),
            )
        }
        onboarding.markWelcomeCompleted()
        nav.finishSetupToDashboard()
    }

    // Tapping a type commits it and advances; the Bluetooth host's type is fixed
    // (its cards aren't tappable), so it advances via the Next button instead.
    private fun pickType(type: Int) {
        viewModel.setType(type)
        if (!current.isBluetoothHost) goTo(afterType())
    }

    private fun advance() {
        when (step) {
            Step.TYPE -> goTo(afterType())
            Step.SESSION -> goTo(Step.FEEL)
            Step.FEEL -> goTo(Step.REVIEW)
            Step.REVIEW -> viewModel.apply()
        }
    }

    private fun handleBack() {
        when (step) {
            Step.REVIEW -> goTo(Step.FEEL)
            Step.FEEL -> goTo(if (current.isMoonlightHost) Step.SESSION else Step.TYPE)
            Step.SESSION -> goTo(Step.TYPE)
            Step.TYPE -> finish()
        }
    }

    // Only a Moonlight destination has a session to settle, so every other host walks
    // straight from the type to the feel step as it always did.
    private fun afterType(): Step = if (current.isMoonlightHost) Step.SESSION else Step.FEEL

    private fun goTo(next: Step) {
        step = next
        binding.scroll.scrollTo(0, 0)
        if (current.loaded) render(current)
    }

    private fun inputLinkLabel(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ): String =
        when (snapshot.link) {
            BindingLink.USB ->
                if (state.draft?.directOn == true) {
                    getString(R.string.setup_cfg_link_usb_direct)
                } else {
                    getString(R.string.setup_cfg_link_usb_standard)
                }
            BindingLink.BLUETOOTH -> getString(R.string.binding_link_bluetooth)
            BindingLink.ONSCREEN -> getString(R.string.binding_link_onscreen)
        }

    // A Direct claim swaps the framework id for a synthetic one, so the slot id
    // from the input step can be dead by the time configure loads; fall back to
    // the one physical controller present rather than a slot that reads input-lost.
    private fun resolveSlotId(slotId: String): String {
        if (slotId == VIRTUAL_SLOT_ID) return slotId
        val devices = gamepadRegistry.devices.value
        if (slotId.toIntOrNull()?.let { devices.containsKey(it) } == true) return slotId
        val sole = devices.values.singleOrNull { !it.isDisconnecting }
        return sole?.id?.toString() ?: slotId
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE
}

// The graph chooses its own format arguments, so the screen fills a string without knowing which
// node it is drawing; this is the Context end of that seam.
private class ContextStrings(
    private val ctx: Context,
) : StringLookup {
    override fun format(
        @StringRes res: Int,
        vararg args: Any,
    ): String = ctx.getString(res, *args)
}
