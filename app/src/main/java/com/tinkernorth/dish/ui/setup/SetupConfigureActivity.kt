// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.databinding.ActivitySetupConfigureBinding
import com.tinkernorth.dish.databinding.SetupReviewCardBinding
import com.tinkernorth.dish.databinding.SetupTypeCardBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.setupDishToolbar
import com.tinkernorth.dish.ui.main.ApplyState
import com.tinkernorth.dish.ui.main.BindingLink
import com.tinkernorth.dish.ui.main.BindingSnapshot
import com.tinkernorth.dish.ui.main.ConfigUiState
import com.tinkernorth.dish.ui.main.ConfigureBindingsViewModel
import com.tinkernorth.dish.ui.main.MainActivity
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import com.tinkernorth.dish.ui.main.iconRes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 4 of the guided flow: type + capability table (4A), feel (4B), review &
// bind (4C). Reuses ConfigureBindingsViewModel for gating, the USB-direct apply,
// and the bind round-trip; this screen only renders sub-steps and feeds the
// wizard's chosen destination in via setHost.
@AndroidEntryPoint
class SetupConfigureActivity : AppCompatActivity() {
    @Inject lateinit var onboarding: OnboardingPreferenceStore

    @Inject lateinit var notifications: DishNotifications

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    private lateinit var binding: ActivitySetupConfigureBinding
    private val viewModel: ConfigureBindingsViewModel by viewModels()

    private var step = Step.TYPE
    private var current = ConfigUiState()

    // The slot the screen actually loaded (a USB Direct claim can retire the id from the prior
    // step); the type cards resolve their candidate capabilities against this same id.
    private var resolvedSlotId = VIRTUAL_SLOT_ID

    private enum class Step { TYPE, FEEL, REVIEW }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        wireSetupSkip(binding.toolbar, onboarding)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        binding.breadcrumb.applyStep(SETUP_STEP_BINDING)

        val slotId = intent.getStringExtra(SetupFlow.EXTRA_SLOT_ID)
        val connectionId = intent.getStringExtra(SetupFlow.EXTRA_CONNECTION_ID)
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

        binding.segOff.setOnClickListener { viewModel.setTouchpad(TouchpadModeValue.OFF) }
        binding.segPad.setOnClickListener { viewModel.setTouchpad(TouchpadModeValue.DS4) }
        binding.segMouse.setOnClickListener { viewModel.setTouchpad(TouchpadModeValue.MOUSE) }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { state ->
                    current = state
                    if (state.loaded) render(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.applyState.collect { renderApplyState(it) }
            }
        }
    }

    private fun render(state: ConfigUiState) {
        val snapshot = state.snapshot ?: return
        binding.groupType.visibility = visibleIf(step == Step.TYPE)
        binding.groupFeel.visibility = visibleIf(step == Step.FEEL)
        binding.groupReview.visibility = visibleIf(step == Step.REVIEW)
        when (step) {
            Step.TYPE -> renderType(state)
            Step.FEEL -> renderFeel(state)
            Step.REVIEW -> renderReview(state, snapshot)
        }
    }

    // 4A. Each card carries the capability table computed for THAT candidate type
    // so the trade-off (PlayStation unlocks motion/touchpad) is visible before the
    // pick. A Bluetooth host has its type fixed upstream, so only the chosen one
    // shows and the cards stop being tappable.
    private fun renderType(state: ConfigUiState) {
        binding.tvTitle.setText(R.string.setup_cfg_type_title)
        binding.tvSubtitle.setText(
            if (state.isBluetoothHost) R.string.setup_cfg_type_locked_subtitle else R.string.setup_cfg_type_subtitle,
        )
        binding.btnContinue.setText(R.string.setup_cfg_continue)
        // Tapping a type commits and advances; only the locked Bluetooth-host case,
        // where the cards aren't tappable, needs the Next button.
        binding.btnContinue.visibility = visibleIf(state.isBluetoothHost)

        val selectedType = state.draft?.type ?: CONTROLLER_TYPE_XBOX
        val locked = state.isBluetoothHost
        bindTypeCard(binding.cardTypeXbox, state, CONTROLLER_TYPE_XBOX, locked)
        bindTypeCard(binding.cardTypePlaystation, state, CONTROLLER_TYPE_PLAYSTATION, locked)
        binding.cardTypeXbox.typeCard.visibility = visibleIf(!locked || selectedType == CONTROLLER_TYPE_XBOX)
        binding.cardTypePlaystation.typeCard.visibility = visibleIf(!locked || selectedType == CONTROLLER_TYPE_PLAYSTATION)
    }

    private fun bindTypeCard(
        card: SetupTypeCardBinding,
        state: ConfigUiState,
        candidateType: Int,
        locked: Boolean,
    ) {
        card.typeTitle.text = viewModel.typeLabel(candidateType)
        card.typeChevron.visibility = visibleIf(!locked)
        card.typeCard.isClickable = !locked
        card.capabilityContainer.bindCapabilityRows(
            capabilityRows(
                viewModel.capabilityForCandidate(
                    slotId = resolvedSlotId,
                    candidateType = candidateType,
                    candidateHostKind = state.selectedHost?.kind ?: ConnectionKind.SATELLITE,
                    candidateHostId = state.draft?.hostId,
                ),
            ),
        )
    }

    // 4B mirrors ConfigureBindingsActivity.bindBindingSection gating exactly: only
    // the rows the current input/destination/type combination supports appear, and
    // listeners are nulled before state is written so re-render never echoes back.
    private fun renderFeel(state: ConfigUiState) {
        binding.tvTitle.setText(R.string.setup_cfg_feel_title)
        binding.tvSubtitle.setText(R.string.setup_cfg_feel_subtitle)
        binding.btnContinue.setText(R.string.setup_cfg_continue)
        binding.btnContinue.visibility = View.VISIBLE

        val touchpadVisible = state.touchpadAvailable
        binding.touchpadRow.visibility = visibleIf(touchpadVisible)
        if (touchpadVisible) {
            binding.segPad.visibility = visibleIf(state.padModeAvailable)
            var selected = state.draft?.touchpadMode ?: TouchpadModeValue.OFF
            if (selected == TouchpadModeValue.DS4 && !state.padModeAvailable) selected = TouchpadModeValue.OFF
            binding.segOff.isSelected = selected == TouchpadModeValue.OFF
            binding.segPad.isSelected = selected == TouchpadModeValue.DS4
            binding.segMouse.isSelected = selected == TouchpadModeValue.MOUSE
        }

        val motionVisible = state.motionAvailable
        binding.motionDivider.visibility = visibleIf(motionVisible && touchpadVisible)
        binding.motionRow.visibility = visibleIf(motionVisible)
        if (motionVisible) {
            binding.swMotion.setOnCheckedChangeListener(null)
            binding.swMotion.isChecked = state.draft?.motionOn == true
            binding.swMotion.setOnCheckedChangeListener { _, isChecked -> viewModel.setMotion(isChecked) }
        }

        // Rumble shows when the path can carry it: a Satellite host returns it, the phone
        // vibrates as a fallback for the on-screen pad, and a physical pad needs its own motor.
        val rumbleVisible = state.capabilities.isAvailable(Feature.RUMBLE)
        binding.rumbleDivider.visibility = visibleIf(rumbleVisible && (motionVisible || touchpadVisible))
        binding.rumbleRow.visibility = visibleIf(rumbleVisible)
        if (rumbleVisible) {
            binding.swRumble.setOnCheckedChangeListener(null)
            binding.swRumble.isChecked = state.draft?.rumbleOn == true
            binding.swRumble.setOnCheckedChangeListener { _, isChecked -> viewModel.setRumble(isChecked) }
        }

        binding.tvFeelEmpty.visibility = visibleIf(!touchpadVisible && !motionVisible && !rumbleVisible)
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
        reviewNodes(state, snapshot).forEach { node ->
            val card = SetupReviewCardBinding.inflate(layoutInflater, container, false)
            card.reviewIcon.setImageResource(node.icon)
            card.reviewKind.setText(node.kind)
            card.reviewName.text = node.name
            card.reviewSublabel.text = node.sublabel
            bindReviewFlows(card.reviewSendsRow, card.reviewSendsChips, node.sends)
            bindReviewFlows(card.reviewGetsRow, card.reviewGetsChips, node.gets)
            container.addView(card.root)
        }
    }

    private fun reviewNodes(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ): List<ReviewNode> {
        val caps = state.capabilities
        val touchpadMode = state.draft?.touchpadMode ?: TouchpadModeValue.OFF
        // Each mode rides its own capability: the DS4 pad needs a touchpad-bearing type,
        // the mouse needs a host that accepts mouse control. The user's chosen mode picks which.
        val padMode = touchpadMode == TouchpadModeValue.DS4 && caps.isAvailable(Feature.TOUCHPAD)
        val mouseMode = touchpadMode == TouchpadModeValue.MOUSE && caps.isAvailable(Feature.MOUSE)
        val model =
            ReviewModel(
                motionOn = caps.isAvailable(Feature.MOTION) && state.draft?.motionOn == true,
                touchpadOn = padMode || mouseMode,
                mouseMode = mouseMode,
                padMode = padMode,
                // Rumble flows back only where the path carries it (no Bluetooth return channel,
                // no phone fallback for a motorless physical pad); the user's toggle gates it.
                rumbleOn = caps.isAvailable(Feature.RUMBLE) && state.draft?.rumbleOn == true,
            )
        return inputNodes(state, snapshot, model) + destinationNodes(state, model)
    }

    // The phone is always one "virtual controller": when it IS the input, the
    // on-screen pad and its touch surface (mouse/touchpad) merge into a single
    // node; when a connected controller is the input, the phone's touch surface is
    // still shown as its own whole virtual-controller input.
    private fun inputNodes(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
        model: ReviewModel,
    ): List<ReviewNode> {
        val gamepad = ReviewFlow(R.drawable.ic_gamepad, R.string.setup_cfg_flow_controller)
        val motion = ReviewFlow(R.drawable.ic_motion, R.string.binding_func_gyro)
        val rumble = ReviewFlow(R.drawable.ic_rumble, R.string.binding_func_rumble)
        val pointer =
            if (model.mouseMode) {
                ReviewFlow(R.drawable.ic_mouse, R.string.touchpad_mode_mouse)
            } else {
                ReviewFlow(R.drawable.ic_touchpad, R.string.touchpad_mode_pad)
            }
        val gets = if (model.rumbleOn) listOf(rumble) else emptyList()
        val virtual =
            ReviewNode(
                kind = R.string.binding_label_input,
                icon = R.drawable.ic_gamepad_virtual,
                name = getString(R.string.default_virtual_controller_name),
                sublabel = getString(R.string.binding_link_onscreen),
                sends = listOf(pointer),
                gets = emptyList(),
            )

        if (snapshot.link == BindingLink.ONSCREEN) {
            return listOf(
                virtual.copy(
                    sends =
                        buildList {
                            add(gamepad)
                            if (model.motionOn) add(motion)
                            if (model.touchpadOn) add(pointer)
                        },
                    gets = gets,
                ),
            )
        }
        val controller =
            ReviewNode(
                kind = R.string.binding_label_input,
                icon = snapshot.link.iconRes(),
                name = snapshot.name,
                sublabel = inputLink(state, snapshot).first,
                sends =
                    buildList {
                        add(gamepad)
                        if (model.motionOn) add(motion)
                    },
                gets = gets,
            )
        return if (model.touchpadOn) listOf(controller, virtual) else listOf(controller)
    }

    private fun destinationNodes(
        state: ConfigUiState,
        model: ReviewModel,
    ): List<ReviewNode> {
        val gamepad = ReviewFlow(R.drawable.ic_gamepad, R.string.setup_cfg_flow_controller)
        val motion = ReviewFlow(R.drawable.ic_motion, R.string.binding_func_gyro)
        val rumble = ReviewFlow(R.drawable.ic_rumble, R.string.binding_func_rumble)
        val rumbleBack = if (model.rumbleOn) listOf(rumble) else emptyList()
        if (state.isBluetoothHost) {
            return listOf(
                ReviewNode(
                    kind = R.string.binding_label_destination,
                    icon = R.drawable.ic_bluetooth,
                    name = state.selectedHost?.label.orEmpty(),
                    sublabel = getString(R.string.setup_cfg_dest_bluetooth),
                    sends = rumbleBack,
                    gets = listOf(gamepad),
                ),
            )
        }
        // Satellite injects the mouse itself; the virtual pad it creates carries the
        // gamepad, motion, DS4 touchpad, and the rumble it sends back.
        val mouse = ReviewFlow(R.drawable.ic_mouse, R.string.touchpad_mode_mouse)
        val touchpad = ReviewFlow(R.drawable.ic_touchpad, R.string.touchpad_mode_pad)
        return listOf(
            ReviewNode(
                kind = R.string.binding_label_destination,
                icon = R.drawable.ic_satellite,
                name = state.selectedHost?.label.orEmpty(),
                sublabel = getString(R.string.setup_cfg_dest_satellite),
                sends = emptyList(),
                gets = if (model.mouseMode) listOf(mouse) else emptyList(),
            ),
            ReviewNode(
                kind = R.string.binding_label_destination,
                icon = R.drawable.ic_gamepad,
                name = viewModel.typeLabel(state.draft?.type ?: CONTROLLER_TYPE_XBOX),
                sublabel = getString(R.string.setup_cfg_virtual_sublabel),
                sends = rumbleBack,
                gets =
                    buildList {
                        add(gamepad)
                        if (model.motionOn) add(motion)
                        if (model.padMode) add(touchpad)
                    },
            ),
        )
    }

    private data class ReviewModel(
        val motionOn: Boolean,
        val touchpadOn: Boolean,
        val mouseMode: Boolean,
        val padMode: Boolean,
        val rumbleOn: Boolean,
    )

    private data class ReviewNode(
        @StringRes val kind: Int,
        @DrawableRes val icon: Int,
        val name: String,
        val sublabel: String,
        val sends: List<ReviewFlow>,
        val gets: List<ReviewFlow>,
    )

    private fun renderApplyState(state: ApplyState) {
        when (state) {
            is ApplyState.Idle -> setBindBusy(false)
            is ApplyState.Running -> setBindBusy(true)
            is ApplyState.Finished -> {
                setBindBusy(false)
                if (state.errorMessage != null) {
                    SetupErrorDialog.show(this, state.errorMessage) { viewModel.apply() }
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
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    // Tapping a type commits it and advances; the Bluetooth host's type is fixed
    // (its cards aren't tappable), so it advances via the Next button instead.
    private fun pickType(type: Int) {
        viewModel.setType(type)
        if (!current.isBluetoothHost) goTo(Step.FEEL)
    }

    private fun advance() {
        when (step) {
            Step.TYPE -> goTo(Step.FEEL)
            Step.FEEL -> goTo(Step.REVIEW)
            Step.REVIEW -> viewModel.apply()
        }
    }

    private fun handleBack() {
        when (step) {
            Step.REVIEW -> goTo(Step.FEEL)
            Step.FEEL -> goTo(Step.TYPE)
            Step.TYPE -> finish()
        }
    }

    private fun goTo(next: Step) {
        step = next
        binding.scroll.scrollTo(0, 0)
        if (current.loaded) render(current)
    }

    private fun inputLink(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ): Pair<String, Int> =
        when (snapshot.link) {
            BindingLink.USB ->
                if (state.draft?.directOn == true) {
                    getString(R.string.setup_cfg_link_usb_direct) to R.drawable.ic_bolt
                } else {
                    getString(R.string.setup_cfg_link_usb_standard) to R.drawable.ic_usb
                }
            BindingLink.BLUETOOTH -> getString(R.string.binding_link_bluetooth) to R.drawable.ic_bluetooth
            BindingLink.ONSCREEN -> getString(R.string.binding_link_onscreen) to R.drawable.ic_gamepad_virtual
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
