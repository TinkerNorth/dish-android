// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.Intent
import android.content.res.ColorStateList
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
import com.tinkernorth.dish.databinding.ActivitySetupConfigureBinding
import com.tinkernorth.dish.databinding.BindingPillBinding
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

    private enum class Step { TYPE, FEEL, REVIEW }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        binding.breadcrumb.applyStep(SETUP_STEP_BINDING)

        val slotId = intent.getStringExtra(SetupFlow.EXTRA_SLOT_ID)
        val connectionId = intent.getStringExtra(SetupFlow.EXTRA_CONNECTION_ID)
        if (slotId == null || connectionId == null) {
            finish()
            return
        }

        viewModel.load(resolveSlotId(slotId))
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
            Step.TYPE -> renderType(state, snapshot)
            Step.FEEL -> renderFeel(state, snapshot)
            Step.REVIEW -> renderReview(state, snapshot)
        }
    }

    // 4A. Each card carries the capability table computed for THAT candidate type
    // so the trade-off (PlayStation unlocks motion/touchpad) is visible before the
    // pick. A Bluetooth host has its type fixed upstream, so only the chosen one
    // shows and the cards stop being tappable.
    private fun renderType(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
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
        bindTypeCard(binding.cardTypeXbox, state, snapshot, CONTROLLER_TYPE_XBOX, selectedType, locked)
        bindTypeCard(binding.cardTypePlaystation, state, snapshot, CONTROLLER_TYPE_PLAYSTATION, selectedType, locked)
        binding.cardTypeXbox.typeCard.visibility = visibleIf(!locked || selectedType == CONTROLLER_TYPE_XBOX)
        binding.cardTypePlaystation.typeCard.visibility = visibleIf(!locked || selectedType == CONTROLLER_TYPE_PLAYSTATION)
    }

    private fun bindTypeCard(
        card: SetupTypeCardBinding,
        state: ConfigUiState,
        snapshot: BindingSnapshot,
        candidateType: Int,
        selectedType: Int,
        locked: Boolean,
    ) {
        card.typeTitle.text = viewModel.typeLabel(candidateType)
        card.typeChevron.visibility = visibleIf(!locked)
        card.typeCard.isClickable = !locked
        val selected = candidateType == selectedType
        card.typeCard.strokeWidth = if (selected) resources.getDimensionPixelSize(R.dimen.wizard_option_stroke_selected) else 0
        card.typeCard.strokeColor = getColor(if (selected) R.color.colorPrimary else android.R.color.transparent)
        card.capabilityContainer.bindCapabilityRows(
            SetupCapability.rows(
                isPlayStation = candidateType == CONTROLLER_TYPE_PLAYSTATION,
                destinationIsSatellite = state.selectedHost?.kind == ConnectionKind.SATELLITE,
                hasDestination = state.hostChosen,
                hasGyro = snapshot.hasGyro,
                hasRumble = snapshot.hasRumble,
            ),
        )
    }

    // 4B mirrors ConfigureBindingsActivity.bindBindingSection gating exactly: only
    // the rows the current input/destination/type combination supports appear, and
    // listeners are nulled before state is written so re-render never echoes back.
    private fun renderFeel(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
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

        val rumbleVisible = snapshot.hasRumble
        binding.rumbleDivider.visibility = visibleIf(rumbleVisible && (motionVisible || touchpadVisible))
        binding.rumbleRow.visibility = visibleIf(rumbleVisible)

        binding.tvFeelEmpty.visibility = visibleIf(!touchpadVisible && !motionVisible && !rumbleVisible)
    }

    private fun renderReview(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        binding.tvTitle.setText(R.string.setup_cfg_review_title)
        binding.tvSubtitle.setText(R.string.setup_cfg_review_subtitle)
        binding.btnContinue.setText(R.string.setup_cfg_bind)
        binding.btnContinue.visibility = View.VISIBLE

        binding.ivReviewInputIcon.setImageResource(snapshot.link.iconRes())
        binding.tvReviewInputName.text = snapshot.name
        val (linkLabel, linkIcon) = inputLink(state, snapshot)
        binding.ivReviewLinkIcon.setImageResource(linkIcon)
        binding.tvReviewLink.text = linkLabel
        bindReviewCaps(state, snapshot)

        val bt = state.isBluetoothHost
        binding.ivReviewDestIcon.setImageResource(if (bt) R.drawable.ic_bluetooth else R.drawable.ic_satellite)
        binding.tvReviewDestName.text = state.selectedHost?.label.orEmpty()
        binding.ivReviewDestLinkIcon.setImageResource(if (bt) R.drawable.ic_bluetooth else R.drawable.ic_overlay_link)
        binding.tvReviewDestLink.setText(if (bt) R.string.setup_cfg_dest_bluetooth else R.string.setup_cfg_dest_satellite)
    }

    private fun bindReviewCaps(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        val rows =
            SetupCapability.rows(
                isPlayStation = (state.draft?.type ?: CONTROLLER_TYPE_XBOX) == CONTROLLER_TYPE_PLAYSTATION,
                destinationIsSatellite = state.selectedHost?.kind == ConnectionKind.SATELLITE,
                hasDestination = state.hostChosen,
                hasGyro = snapshot.hasGyro,
                hasRumble = snapshot.hasRumble,
            )
        val container = binding.reviewCapsContainer
        container.removeAllViews()
        rows.filter { it.available }.forEach { row ->
            when (row.kind) {
                SetupCapabilityKind.RUMBLE -> container.addView(capPill(R.drawable.ic_rumble, R.string.binding_func_rumble))
                SetupCapabilityKind.MOTION -> container.addView(capPill(R.drawable.ic_motion, R.string.binding_func_gyro))
                SetupCapabilityKind.TOUCHPAD -> container.addView(capPill(R.drawable.ic_touchpad, R.string.binding_func_touchpad))
            }
        }
        if (container.childCount == 0) {
            container.addView(capPill(R.drawable.ic_cancel, R.string.setup_cfg_review_none))
        }
    }

    private fun capPill(
        @DrawableRes icon: Int,
        @StringRes label: Int,
    ): View {
        val pill = BindingPillBinding.inflate(layoutInflater, binding.reviewCapsContainer, false)
        pill.root.setBackgroundResource(R.drawable.bg_binding_pill_cap)
        pill.ivPillIcon.setImageResource(icon)
        pill.ivPillIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.colorOnSurfaceVariant))
        pill.tvPillText.setText(label)
        pill.tvPillText.setTextColor(getColor(R.color.colorOnSurfaceVariant))
        return pill.root
    }

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
