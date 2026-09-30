// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.setup

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.ui.main.StringLookup

// One thing a node in the data-flow sends or gets: a feature icon plus its label.
// Shared by the configure review and the destination picker so a destination's
// capabilities read identically wherever they appear.
data class ReviewFlow(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
)

internal val GAMEPAD_FLOW = ReviewFlow(R.drawable.ic_gamepad, R.string.setup_cfg_flow_controller)
internal val MOTION_FLOW = ReviewFlow(R.drawable.ic_motion, R.string.binding_func_gyro)
internal val BATTERY_FLOW = ReviewFlow(R.drawable.ic_battery, R.string.setup_cap_battery)
internal val MIC_FLOW = ReviewFlow(R.drawable.ic_mic, R.string.setup_cap_mic)
internal val RUMBLE_FLOW = ReviewFlow(R.drawable.ic_rumble, R.string.binding_func_rumble)
internal val TOUCHPAD_FLOW = ReviewFlow(R.drawable.ic_touchpad, R.string.touchpad_mode_pad)
internal val MOUSE_FLOW = ReviewFlow(R.drawable.ic_mouse, R.string.touchpad_mode_mouse)
internal val UNKNOWN_FLOW = ReviewFlow(R.drawable.ic_help, R.string.setup_cap_unknown)

// What the review says the binding carries, resolved once from the capability table and the
// user's toggles so every node reads from the same facts.
internal data class ReviewModel(
    val motionOn: Boolean,
    val touchpadOn: Boolean,
    val mouseMode: Boolean,
    val padMode: Boolean,
    val rumbleOn: Boolean,
    val batteryOn: Boolean,
    val triggerRumbleOn: Boolean,
    val lightbar: Boolean,
    val triggerEffects: Boolean,
    val playerLeds: Boolean,
    val micOn: Boolean,
    val speakerOn: Boolean,
)

internal data class ReviewNode(
    @StringRes val kind: Int,
    @DrawableRes val icon: Int,
    val name: String,
    val sublabel: String,
    val sends: List<ReviewFlow>,
    val gets: List<ReviewFlow>,
    val compat: DishProtocolCompat = DishProtocolCompat.UNKNOWN,
)

// The facts the graph needs about the two ends of the binding, gathered by the screen from its
// state and ViewModel so the graph itself depends on neither.
internal data class ReviewInput(
    val onScreenInput: Boolean,
    val inputName: String,
    @DrawableRes val inputIcon: Int,
    val inputLinkLabel: String,
    val inputUnknown: Boolean,
    val hostKind: ConnectionKind,
    val hostLabel: String,
    val hostCompat: DishProtocolCompat,
    val padTypeLabel: String,
    val moonlightAddress: String,
)

// Routing is derived, not picked, and the two pointer surfaces coexist, so the summary shows
// every pointer flow the path can carry. Rumble, trigger rumble, mic and speaker follow their
// toggles; the rest of the feedback follows the capability table alone.
internal fun reviewModelFor(
    caps: SlotCapabilities,
    motionOn: Boolean,
    rumbleOn: Boolean,
    micOn: Boolean,
    speakerOn: Boolean,
): ReviewModel {
    val padMode = caps.isAvailable(Feature.TOUCHPAD)
    val mouseMode = caps.isAvailable(Feature.MOUSE)
    return ReviewModel(
        motionOn = caps.isAvailable(Feature.MOTION) && motionOn,
        touchpadOn = padMode || mouseMode,
        mouseMode = mouseMode,
        padMode = padMode,
        rumbleOn = caps.isAvailable(Feature.RUMBLE) && rumbleOn,
        batteryOn = caps.isAvailable(Feature.BATTERY),
        triggerRumbleOn = caps.isAvailable(Feature.TRIGGER_RUMBLE) && rumbleOn,
        lightbar = caps.isAvailable(Feature.LIGHTBAR),
        triggerEffects = caps.isAvailable(Feature.TRIGGER_EFFECTS),
        playerLeds = caps.isAvailable(Feature.PLAYER_LEDS),
        micOn = caps.isAvailable(Feature.MIC) && micOn,
        speakerOn = caps.isAvailable(Feature.SPEAKER) && speakerOn,
    )
}

// One card per source and destination, each showing what it sends (up) and gets (down) so the
// whole data flow is visible before binding.
internal fun reviewGraph(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): List<ReviewNode> = inputNodes(input, model, strings) + destinationNodes(input, model, strings)

// Everything the host can push back at this pad, in a fixed order; the input's gets and the
// emulated pad's sends are the same list by definition.
internal fun feedbackFlows(model: ReviewModel): List<ReviewFlow> =
    buildList {
        if (model.rumbleOn) add(RUMBLE_FLOW)
        if (model.triggerRumbleOn) add(ReviewFlow(R.drawable.ic_trigger_rumble, R.string.setup_cap_trigger_rumble))
        if (model.lightbar) add(ReviewFlow(R.drawable.ic_lightbar, R.string.setup_cap_lightbar))
        if (model.triggerEffects) add(ReviewFlow(R.drawable.ic_trigger_effects, R.string.setup_cap_trigger_effects))
        if (model.playerLeds) add(ReviewFlow(R.drawable.ic_player_leds, R.string.setup_cap_player_leds))
        if (model.speakerOn) add(ReviewFlow(R.drawable.ic_speaker, R.string.setup_cap_speaker))
    }

// The phone is always one "virtual controller": when it IS the input, the on-screen pad and its
// touch surface merge into a single node; when a connected controller is the input, the phone's
// touch surface is still shown as its own whole virtual-controller input.
private fun inputNodes(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): List<ReviewNode> =
    if (input.onScreenInput) {
        listOf(onScreenInputNode(model, strings))
    } else {
        controllerInputNodes(input, model, strings)
    }

private fun pointerFlows(model: ReviewModel): List<ReviewFlow> =
    buildList {
        if (model.padMode) add(TOUCHPAD_FLOW)
        if (model.mouseMode) add(MOUSE_FLOW)
    }

private fun phoneInputNode(
    model: ReviewModel,
    strings: StringLookup,
): ReviewNode =
    ReviewNode(
        kind = R.string.binding_label_input,
        icon = R.drawable.ic_gamepad_virtual,
        name = strings.format(R.string.default_virtual_controller_name),
        sublabel = strings.format(R.string.binding_link_onscreen),
        sends = pointerFlows(model),
        gets = emptyList(),
    )

private fun onScreenInputNode(
    model: ReviewModel,
    strings: StringLookup,
): ReviewNode =
    phoneInputNode(model, strings).copy(
        sends =
            buildList {
                add(GAMEPAD_FLOW)
                if (model.motionOn) add(MOTION_FLOW)
                addAll(pointerFlows(model))
                if (model.batteryOn) add(BATTERY_FLOW)
                if (model.micOn) add(MIC_FLOW)
            },
        gets = feedbackFlows(model),
    )

private fun controllerInputNodes(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): List<ReviewNode> {
    val controller =
        ReviewNode(
            kind = R.string.binding_label_input,
            icon = input.inputIcon,
            name = input.inputName,
            sublabel = input.inputLinkLabel,
            sends =
                buildList {
                    add(GAMEPAD_FLOW)
                    if (model.motionOn) add(MOTION_FLOW)
                    if (model.batteryOn) add(BATTERY_FLOW)
                    if (model.micOn) add(MIC_FLOW)
                    if (input.inputUnknown) add(UNKNOWN_FLOW)
                },
            gets = feedbackFlows(model),
        )
    return if (model.touchpadOn) listOf(controller, phoneInputNode(model, strings)) else listOf(controller)
}
