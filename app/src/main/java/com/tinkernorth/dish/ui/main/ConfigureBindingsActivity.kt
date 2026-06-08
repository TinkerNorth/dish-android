// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.databinding.ActivityConfigureBindingsBinding
import com.tinkernorth.dish.databinding.BindingApplyStepBinding
import com.tinkernorth.dish.databinding.BindingValueNoneBinding
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.ui.common.DishNavigator
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.attachGamepadHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ConfigureBindingsActivity : AppCompatActivity() {
    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    private lateinit var binding: ActivityConfigureBindingsBinding
    private lateinit var gamepadHost: GamepadActivityHost
    private val viewModel: ConfigureBindingsViewModel by viewModels()
    private val nav by lazy { DishNavigator(this) }

    private var current: ConfigUiState = ConfigUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigureBindingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        gamepadHost = attachGamepadHost(binding.root, wakeState, gamepadRegistry, notifications)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()

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

    override fun onStop() {
        super.onStop()
        gamepadHost.cancelDimOnStop()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { state ->
                    current = state
                    if (state.loaded) renderContent(state)
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
        binding.btnApply.isEnabled = state.hostChosen
        binding.btnApply.alpha = if (state.hostChosen) 1f else DISABLED_ALPHA
        binding.btnUnbind.visibility = if (snapshot.bound) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (state.noHosts) View.GONE else View.VISIBLE

        bindInputSection(state, snapshot)
        bindDestinationSection(state, snapshot)
        binding.sectionBinding.root.visibility = if (state.hostChosen) View.VISIBLE else View.GONE
        if (state.hostChosen) bindBindingSection(state)
    }

    private fun bindInputSection(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        val s = binding.sectionInput
        s.ivInputIcon.setImageResource(linkIconForSection(snapshot))
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
        if (fc.childCount == 0) fc.addView(noneValue(fc))
    }

    private fun bindDestinationSection(
        state: ConfigUiState,
        snapshot: BindingSnapshot,
    ) {
        val d = binding.sectionDestination
        d.ivDestIcon.setImageResource(if (state.isBluetoothHost) R.drawable.ic_bluetooth else R.drawable.ic_satellite)
        d.tvDestLabel.text = getString(R.string.binding_label_destination)

        val noHosts = state.noHosts
        d.hostDropdown.visibility = if (noHosts) View.GONE else View.VISIBLE
        if (!noHosts) {
            val host = state.selectedHost
            d.hostDropdown.text = host?.label ?: getString(R.string.binding_choose_destination)
            d.hostDropdown.setTextColor(getColor(if (host != null) R.color.colorOnSurface else R.color.colorMuted))
            d.hostDropdown.setOnClickListener { showHostMenu(d.hostDropdown) }
        }
        d.legendSatellite.visibility = if (state.hostChosen && !state.isBluetoothHost) View.VISIBLE else View.GONE
        d.legendBt.visibility = if (state.hostChosen && state.isBluetoothHost) View.VISIBLE else View.GONE
        d.noHostsGroup.visibility = if (noHosts) View.VISIBLE else View.GONE
        if (noHosts) {
            d.tvNoHostsBody.text = getString(R.string.binding_no_hosts_body, snapshot.name)
            d.btnManage.setOnClickListener { nav.toConnections() }
        }
    }

    private fun bindBindingSection(state: ConfigUiState) {
        val bz = binding.sectionBinding
        val typeLabel = typeLabel(state.draft?.type ?: CONTROLLER_TYPE_XBOX)
        bz.tvEmulateText.text = typeLabel
        bz.emulateDropdown.text = typeLabel
        bz.emulatePill.visibility = if (state.isBluetoothHost) View.VISIBLE else View.GONE
        bz.emulateDropdown.visibility = if (state.isBluetoothHost) View.GONE else View.VISIBLE
        bz.emulateDropdown.setOnClickListener { showTypeMenu(bz.emulateDropdown) }

        val motionVisible = state.motionAvailable
        bz.motionDivider.visibility = if (motionVisible) View.VISIBLE else View.GONE
        bz.motionRow.visibility = if (motionVisible) View.VISIBLE else View.GONE
        if (motionVisible) {
            bz.swMotion.setOnCheckedChangeListener(null)
            bz.swMotion.isChecked = state.draft?.motionOn == true
            bz.swMotion.setOnCheckedChangeListener { _, isChecked -> viewModel.setMotion(isChecked) }
        }

        val touchpadVisible = state.touchpadAvailable
        bz.touchpadDivider.visibility = if (touchpadVisible) View.VISIBLE else View.GONE
        bz.touchpadRow.visibility = if (touchpadVisible) View.VISIBLE else View.GONE
        if (touchpadVisible) {
            bz.segPad.visibility = if (state.padModeAvailable) View.VISIBLE else View.GONE
            var selected = state.draft?.touchpadMode ?: TouchpadModeValue.OFF
            if (selected == TouchpadModeValue.DS4 && !state.padModeAvailable) selected = TouchpadModeValue.OFF
            bz.segOff.isSelected = selected == TouchpadModeValue.OFF
            bz.segPad.isSelected = selected == TouchpadModeValue.DS4
            bz.segMouse.isSelected = selected == TouchpadModeValue.MOUSE
            bz.segOff.setOnClickListener { viewModel.setTouchpad(TouchpadModeValue.OFF) }
            bz.segPad.setOnClickListener { viewModel.setTouchpad(TouchpadModeValue.DS4) }
            bz.segMouse.setOnClickListener { viewModel.setTouchpad(TouchpadModeValue.MOUSE) }
        }
    }

    private fun noneValue(parent: ViewGroup): View = BindingValueNoneBinding.inflate(layoutInflater, parent, false).root

    private fun linkIconForSection(snapshot: BindingSnapshot): Int =
        when (snapshot.link) {
            BindingLink.BLUETOOTH -> R.drawable.ic_bluetooth
            BindingLink.ONSCREEN -> R.drawable.ic_gamepad_virtual
            BindingLink.USB -> R.drawable.ic_gamepad
        }

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

    private fun showHostMenu(anchor: View) {
        val hosts = current.hosts
        val pm = PopupMenu(this, anchor)
        hosts.forEachIndexed { i, h ->
            val hint =
                if (h.kind ==
                    ConnectionKind.BLUETOOTH
                ) {
                    getString(R.string.binding_host_hint_bt)
                } else {
                    getString(R.string.binding_host_hint_satellite)
                }
            pm.menu.add(0, i, i, "${h.label} · $hint")
        }
        pm.setOnMenuItemClickListener { item ->
            hosts.getOrNull(item.itemId)?.let { viewModel.setHost(it.id) }
            true
        }
        pm.show()
    }

    private fun showTypeMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, CONTROLLER_TYPE_PLAYSTATION, 0, getString(R.string.picker_type_playstation))
        pm.menu.add(0, CONTROLLER_TYPE_XBOX, 1, getString(R.string.picker_type_xbox))
        pm.setOnMenuItemClickListener { item ->
            viewModel.setType(item.itemId)
            true
        }
        pm.show()
    }

    private fun typeLabel(type: Int): String =
        getString(if (type == CONTROLLER_TYPE_PLAYSTATION) R.string.picker_type_playstation else R.string.picker_type_xbox)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost.onWindowFocusChanged(hasFocus)
    }

    companion object {
        const val EXTRA_SLOT_ID = "extra_slot_id"

        private const val DISABLED_ALPHA = 0.4f
    }
}
