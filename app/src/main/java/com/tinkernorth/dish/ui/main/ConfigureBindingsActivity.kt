// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.databinding.ActivityConfigureBindingsBinding
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
            getString(if (snapshot.bound) R.string.tz_activity_title else R.string.tz_activity_title_bind)
        binding.btnApply.text =
            getString(if (snapshot.bound) R.string.tz_activity_apply else R.string.tz_activity_bind)
        binding.btnApply.visibility = if (state.noHosts) View.GONE else View.VISIBLE

        binding.content.removeAllViews()
        binding.content.addView(buildInputSection(state))
        binding.content.addView(buildDestinationSection(state))
        if (!state.noHosts) binding.content.addView(buildBindingSection(state))
    }

    private fun buildInputSection(state: ConfigUiState): View {
        val snapshot = state.snapshot!!
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val connRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        connRow.addView(microLabel(getString(R.string.tz_label_connection)), LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        val (linkLabel, linkIcon) =
            when (snapshot.link) {
                TzLink.USB -> getString(R.string.tz_link_usb) to R.drawable.ic_usb
                TzLink.BLUETOOTH -> getString(R.string.tz_link_bluetooth) to R.drawable.ic_bluetooth
                TzLink.ONSCREEN -> getString(R.string.tz_link_onscreen) to R.drawable.ic_gamepad_virtual
            }
        connRow.addView(factPill(linkLabel, linkIcon))
        connRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val guessed = snapshot.link == TzLink.USB && state.draft?.directOn == true && !snapshot.directVerified
        if (snapshot.link == TzLink.USB && snapshot.directCapable) {
            connRow.addView(directToggle(state, guessed))
        }
        content.addView(connRow, rowParams(dp(6)))

        if (guessed) content.addView(guessedCallout(), rowParams(dp(10)))

        content.addView(thinDivider(), rowParams(dp(12)))

        val fnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fnRow.addView(microLabel(getString(R.string.tz_label_functions)), LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        val caps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (snapshot.hasRumble) caps.addView(capPill(getString(R.string.tz_func_rumble), R.drawable.ic_rumble), chipParams(caps.childCount))
        if (snapshot.hasGyro) caps.addView(capPill(getString(R.string.tz_func_gyro), R.drawable.ic_motion), chipParams(caps.childCount))
        if (caps.childCount == 0) caps.addView(noneText())
        fnRow.addView(caps, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(fnRow, rowParams(dp(6)))

        return sectionView(linkIconForSection(snapshot), getString(R.string.tz_label_input), snapshot.name, content, first = true)
    }

    private fun linkIconForSection(snapshot: TzSnapshot): Int =
        when (snapshot.link) {
            TzLink.BLUETOOTH -> R.drawable.ic_bluetooth
            TzLink.ONSCREEN -> R.drawable.ic_gamepad_virtual
            TzLink.USB -> R.drawable.ic_gamepad
        }

    private fun directToggle(
        state: ConfigUiState,
        guessed: Boolean,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val on = state.draft?.directOn == true
        val accent = if (guessed) color(R.color.colorWarning) else color(R.color.colorPrimary)
        val labelColor = if (on) accent else color(R.color.colorMuted)
        val label = TextView(this).apply {
            text = getString(R.string.tz_mode_direct)
            setTextColor(labelColor)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val bolt = AppCompatResources.getDrawable(this@ConfigureBindingsActivity, R.drawable.ic_bolt)
            val s = dp(14)
            bolt?.setBounds(0, 0, s, s)
            setCompoundDrawablesRelative(bolt, null, null, null)
            compoundDrawablePadding = dp(4)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(labelColor))
        }
        box.addView(label)
        val sw = MaterialSwitch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, isChecked -> viewModel.setDirect(isChecked) }
        }
        box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        return box
    }

    private fun guessedCallout(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dp(11), dp(10), dp(11), dp(10))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(6).toFloat()
            bg.setColor(color(R.color.colorTertiaryContainer))
            background = bg
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_warning)
            imageTintList = ColorStateList.valueOf(color(R.color.colorOnTertiaryContainer))
        }
        box.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(1); marginEnd = dp(9) })
        val text = TextView(this).apply {
            text = getString(R.string.path_risk_guessed)
            setTextColor(color(R.color.colorOnTertiaryContainer))
            textSize = 12f
            setLineSpacing(0f, 1.3f)
        }
        box.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return box
    }

    private fun buildDestinationSection(state: ConfigUiState): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (state.noHosts) {
            val manage = inflateAction(content, R.drawable.ic_satellite, getString(R.string.tz_manage_destinations))
            manage.setOnClickListener { nav.toConnections() }
            content.addView(manage)
            content.addView(noHostsMessage(state), rowParams(dp(14)))
        } else {
            content.addView(dropdown(state.selectedHost?.label ?: "") { anchor -> showHostMenu(anchor) })
            content.addView(destinationLegend(state), rowParams(dp(12)))
        }
        val title =
            state.selectedHost?.let { "${getString(R.string.tz_label_destination)} · ${it.label}" }
                ?: getString(R.string.tz_label_destination)
        val icon = if (state.isBluetoothHost) R.drawable.ic_bluetooth else R.drawable.ic_satellite
        return sectionView(icon, title, null, content, first = false)
    }

    private fun destinationLegend(state: ConfigUiState): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (state.isBluetoothHost) {
            box.addView(legendRow(getString(R.string.tz_dest_carries), listOf(capPill(getString(R.string.tz_func_rumble), R.drawable.ic_rumble))))
            box.addView(
                legendRow(
                    getString(R.string.tz_dest_no_channel),
                    listOf(
                        offPill(getString(R.string.tz_func_gyro), R.drawable.ic_motion),
                        offPill(getString(R.string.tz_func_touchpad), R.drawable.ic_touchpad),
                        offPill(getString(R.string.tz_func_mouse), R.drawable.ic_mouse),
                    ),
                ),
                rowParams(dp(2)),
            )
            val note = TextView(this).apply {
                text = getString(R.string.tz_dest_bt_note)
                setTextColor(color(R.color.colorMuted))
                textSize = 11.5f
                setLineSpacing(0f, 1.3f)
            }
            box.addView(note, rowParams(dp(8)))
        } else {
            box.addView(
                legendRow(
                    getString(R.string.picker_type_playstation),
                    listOf(
                        capPill(getString(R.string.tz_func_gyro), R.drawable.ic_motion),
                        capPill(getString(R.string.tz_func_touchpad), R.drawable.ic_touchpad),
                    ),
                ),
            )
            box.addView(
                legendRow(
                    getString(R.string.picker_type_xbox),
                    listOf(capPill(getString(R.string.tz_func_rumble), R.drawable.ic_rumble)),
                ),
                rowParams(dp(2)),
            )
            box.addView(
                legendRow(
                    getString(R.string.tz_dest_host),
                    listOf(capPill(getString(R.string.tz_func_mouse), R.drawable.ic_mouse)),
                ),
                rowParams(dp(2)),
            )
        }
        return box
    }

    private fun noHostsMessage(state: ConfigUiState): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(6), dp(6), dp(6), dp(2))
        }
        val glyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_satellite_off)
            imageTintList = ColorStateList.valueOf(color(R.color.colorMuted))
        }
        box.addView(glyph, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(6) })
        val title = TextView(this).apply {
            text = getString(R.string.tz_no_hosts_title)
            setTextColor(color(R.color.colorOnSurface))
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        box.addView(title)
        val body = TextView(this).apply {
            text = getString(R.string.tz_no_hosts_body, state.snapshot?.name ?: "")
            setTextColor(color(R.color.colorOnSurfaceVariant))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.35f)
        }
        box.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return box
    }

    private fun buildBindingSection(state: ConfigUiState): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(microLabel(getString(R.string.tz_label_emulate)), rowParams(dp(0)))
        if (state.isBluetoothHost) {
            // Bluetooth type is fixed by the paired HID profile, so it's shown, not chosen.
            content.addView(factPill(typeLabel(state.draft?.type ?: CONTROLLER_TYPE_XBOX), null), rowParams(dp(6)))
        } else {
            content.addView(dropdown(typeLabel(state.draft?.type ?: CONTROLLER_TYPE_XBOX)) { anchor -> showTypeMenu(anchor) }, rowParams(dp(6)))
        }

        if (state.motionAvailable) {
            content.addView(thinDivider(), rowParams(dp(11)))
            content.addView(controlRow(R.drawable.ic_motion, getString(R.string.tz_func_motion), state.draft?.motionOn == true) { on -> viewModel.setMotion(on) }, rowParams(dp(11)))
        }
        if (state.touchpadAvailable) {
            content.addView(thinDivider(), rowParams(dp(11)))
            content.addView(touchpadRow(state), rowParams(dp(11)))
        }

        return sectionView(R.drawable.ic_tune, getString(R.string.tz_label_binding), null, content, first = false, accent = true)
    }

    private fun controlRow(
        @DrawableRes icon: Int,
        label: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iv = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(R.color.colorPrimary))
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(11) })
        val tv = TextView(this).apply {
            text = label
            setTextColor(color(R.color.colorOnSurface))
            textSize = 13.5f
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.addView(sw)
        return row
    }

    private fun touchpadRow(state: ConfigUiState): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_touchpad)
            imageTintList = ColorStateList.valueOf(color(R.color.colorPrimary))
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(11) })
        val tv = TextView(this).apply {
            text = getString(R.string.tz_func_touchpad)
            setTextColor(color(R.color.colorOnSurface))
            textSize = 13.5f
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val options = mutableListOf(TouchpadModeValue.OFF to getString(R.string.touchpad_mode_off))
        if (state.padModeAvailable) options += TouchpadModeValue.DS4 to getString(R.string.touchpad_mode_pad)
        options += TouchpadModeValue.MOUSE to getString(R.string.touchpad_mode_mouse)
        var selected = state.draft?.touchpadMode ?: TouchpadModeValue.OFF
        if (options.none { it.first == selected }) selected = TouchpadModeValue.OFF
        row.addView(segmented(options, selected) { viewModel.setTouchpad(it) })
        return row
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
                renderToast(state)
            }
        }
    }

    private fun renderSteps(state: ApplyState.Running) {
        binding.applySteps.removeAllViews()
        state.steps.forEachIndexed { i, step ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val statusView: View =
                when {
                    i < state.doneCount -> ImageView(this).apply {
                        setImageResource(R.drawable.ic_check_circle)
                        imageTintList = ColorStateList.valueOf(color(R.color.colorSuccess))
                    }
                    i == state.doneCount -> ProgressBar(this).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(color(R.color.colorPrimary))
                    }
                    else -> View(this).apply {
                        val d = GradientDrawable()
                        d.shape = GradientDrawable.OVAL
                        d.setColor(color(R.color.colorMuted))
                        background = d
                    }
                }
            val box = LinearLayout(this).apply { gravity = Gravity.CENTER }
            val size = if (i == state.doneCount) dp(16) else dp(15)
            box.addView(statusView, LinearLayout.LayoutParams(size, size))
            row.addView(box, LinearLayout.LayoutParams(dp(18), dp(18)))
            val tv = TextView(this).apply {
                text = step.label
                setTextColor(color(R.color.colorOnSurface))
                textSize = 13f
            }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            row.alpha = if (i > state.doneCount) 0.5f else 1f
            binding.applySteps.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(11) })
        }
    }

    private fun renderToast(state: ApplyState.Finished) {
        binding.toast.visibility = View.VISIBLE
        val success = state.errorMessage == null
        binding.ivToastIcon.setImageResource(if (success) R.drawable.ic_check_circle else R.drawable.ic_error)
        binding.ivToastIcon.imageTintList =
            ColorStateList.valueOf(color(if (success) R.color.colorSuccess else R.color.colorError))
        binding.tvToastTitle.text =
            getString(if (success) R.string.tz_apply_success_title else R.string.tz_apply_error_title)
        binding.tvToastDetail.visibility = View.VISIBLE
        binding.tvToastDetail.text =
            if (success) {
                getString(R.string.tz_apply_success_detail, state.controllerName, state.hostName)
            } else {
                state.errorMessage
            }
        binding.btnToastAction.text =
            getString(if (success) R.string.tz_apply_action_view else R.string.tz_apply_action_retry)
        binding.btnToastAction.setOnClickListener {
            if (success) finish() else viewModel.apply()
        }
        if (success) {
            binding.root.postDelayed({ if (!isFinishing) finish() }, SUCCESS_DISMISS_MS)
        }
    }

    private fun showHostMenu(anchor: View) {
        val hosts = current.hosts
        val pm = PopupMenu(this, anchor)
        hosts.forEachIndexed { i, h ->
            val hint = if (h.kind == ConnectionKind.BLUETOOTH) getString(R.string.tz_host_hint_bt) else getString(R.string.tz_host_hint_satellite)
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

    private fun sectionView(
        @DrawableRes icon: Int,
        title: String,
        value: String?,
        content: View,
        first: Boolean,
        accent: Boolean = false,
    ): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iv = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(if (accent) R.color.colorPrimary else R.color.colorMuted))
        }
        header.addView(iv, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(7) })
        val label = TextView(this).apply {
            text = title.uppercase()
            setTextColor(color(if (accent) R.color.colorPrimary else R.color.colorMuted))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
        }
        header.addView(label)
        if (value != null) {
            val valueView = TextView(this).apply {
                text = " · $value"
                setTextColor(color(R.color.colorOnSurface))
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
            }
            header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        section.addView(header)
        section.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (!first) lp.topMargin = dp(20)
        section.layoutParams = lp
        return section
    }

    private fun dropdown(
        text: String,
        onClick: (View) -> Unit,
    ): MaterialButton {
        val btn = LayoutInflater.from(this).inflate(R.layout.tz_dropdown, binding.content, false) as MaterialButton
        btn.text = text
        btn.setOnClickListener { onClick(btn) }
        return btn
    }

    private fun inflateAction(
        parent: ViewGroup,
        @DrawableRes icon: Int,
        text: String,
    ): MaterialButton {
        val btn = LayoutInflater.from(this).inflate(R.layout.tz_action_button, parent, false) as MaterialButton
        btn.text = text
        btn.setIconResource(icon)
        return btn
    }

    private fun segmented(
        options: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit,
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val bg = GradientDrawable()
            bg.cornerRadius = dp(4).toFloat()
            bg.setStroke(dp(1), color(R.color.colorOutline))
            background = bg
        }
        options.forEachIndexed { i, (value, label) ->
            val sel = value == selected
            val seg = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(if (sel) color(R.color.colorOnPrimary) else color(R.color.colorOnSurfaceVariant))
                setPadding(dp(12), dp(5), dp(12), dp(5))
                gravity = Gravity.CENTER
                if (sel) setBackgroundColor(color(R.color.colorPrimary))
                setOnClickListener { if (!sel) onSelect(value) }
            }
            container.addView(seg)
            if (i < options.size - 1) {
                container.addView(View(this).apply { setBackgroundColor(color(R.color.colorOutline)) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        return container
    }

    private fun legendRow(
        label: String,
        pills: List<View>,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(microLabel(label), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pills.forEachIndexed { i, v -> box.addView(v, chipParams(i)) }
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun microLabel(label: String): TextView =
        TextView(this).apply {
            text = label.uppercase()
            setTextColor(color(R.color.colorMuted))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
        }

    private fun noneText(): TextView =
        TextView(this).apply {
            text = getString(R.string.tz_card_no_functions)
            setTextColor(color(R.color.colorMuted))
            textSize = 12f
        }

    private fun thinDivider(): View =
        View(this).apply {
            setBackgroundColor(color(R.color.colorOutlineVariant))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

    private fun factPill(
        text: String,
        @DrawableRes icon: Int?,
    ): TextView = pill(text, icon, color(R.color.colorOnSurface), color(R.color.colorSurfaceVariant), color(R.color.colorOutlineVariant))

    private fun capPill(
        text: String,
        @DrawableRes icon: Int?,
    ): TextView = pill(text, icon, color(R.color.colorOnSurfaceVariant), Color.TRANSPARENT, color(R.color.colorOutlineVariant))

    private fun offPill(
        text: String,
        @DrawableRes icon: Int?,
    ): TextView = pill(text, icon, color(R.color.colorMuted), Color.TRANSPARENT, color(R.color.colorOutlineVariant), dashed = true, alpha = 0.6f)

    private fun pill(
        text: String,
        @DrawableRes icon: Int?,
        @ColorInt fg: Int,
        @ColorInt bg: Int,
        @ColorInt stroke: Int,
        dashed: Boolean = false,
        alpha: Float = 1f,
    ): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(fg)
        tv.textSize = 11.5f
        tv.includeFontPadding = false
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.alpha = alpha
        val startPad = if (icon != null) dp(7) else dp(9)
        tv.setPaddingRelative(startPad, dp(4), dp(9), dp(4))
        val gd = GradientDrawable()
        gd.cornerRadius = dp(4).toFloat()
        gd.setColor(bg)
        if (dashed) gd.setStroke(dp(1), stroke, dp(3).toFloat(), dp(2).toFloat()) else gd.setStroke(dp(1), stroke)
        tv.background = gd
        if (icon != null) {
            val d = AppCompatResources.getDrawable(this, icon)
            val s = dp(14)
            d?.setBounds(0, 0, s, s)
            tv.setCompoundDrawablesRelative(d, null, null, null)
            tv.compoundDrawablePadding = dp(4)
            TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(fg))
        }
        return tv
    }

    private fun rowParams(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }

    private fun chipParams(index: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dp(6) }

    @ColorInt
    private fun color(res: Int): Int = getColor(res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

        private const val SUCCESS_DISMISS_MS = 1600L
    }
}
